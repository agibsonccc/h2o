package water;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

import jsr166y.ForkJoinPool;
import water.hdfs.PersistHdfs;
import water.store.s3.PersistS3;

/**
 * The core Value stored in the distributed K/V store.  It contains an
 * underlying byte[] which may be spilled to disk and freed by the
 * {@link MemoryManager}.
 */
public class Value extends Iced implements ForkJoinPool.ManagedBlocker {

  // Max size of Values before we start asserting.
  // Sizes around this big, or larger are probably true errors.
  // In any case, they will cause issues with both GC (giant pause times on
  // many collectors) and I/O (long term blocking of TCP I/O channels to
  // service a single request, causing starvation of other requests).
  public static final int MAX = 10*1024*1024;

  // ---
  // Values are wads of bits; known small enough to 'chunk' politely on disk,
  // or fit in a Java heap (larger Values are built via arraylets) but (much)
  // larger than a UDP packet.  Values can point to either the disk or ram
  // version or both.  There's no caching smarts, nor compression nor de-dup
  // smarts.  This is just a local placeholder for some user bits being held at
  // this local Node.
  public int _max; // Max length of Value bytes

  // ---
  // A array of this Value when cached in DRAM, or NULL if not cached.  The
  // contents of _mem are immutable (Key/Value mappings can be changed by an
  // explicit PUT action).
  private volatile byte[] _mem;
  public final byte[] mem() { return _mem; }

  // ---
  public static final byte FREEZABLE = 0;
  // public static final int COLUMN_DOUBLE = 1;
  // ...
  public static final byte VALUE_ARRAY = 1;
  private byte _type;
  public final byte type() { return _type; }
  public final boolean isArray() {
    return _type == VALUE_ARRAY;
  }

  /** The FAST path get-byte-array - final method for speed.
   * @return a null if the Value is deleted already. */
  public final byte[] memOrLoad() {
    byte[] mem = _mem;          // Read once!
    if( mem != null ) return mem;
    if( _max == 0 ) return (_mem = new byte[0]);
    return (_mem = loadPersist());
  }

  public final void freeMem() { _mem = null; }

  // ---
  // As object.
  public final <T extends Freezable> T get() {
    return new AutoBuffer(memOrLoad()).get();
  }

  public final <T extends Freezable> T get(T t) {
    AutoBuffer bb = new AutoBuffer(memOrLoad());
    char type = bb.get2();
    assert t.frozenType() == type : "" + t.frozenType() + " vs " + (int) type;
    return t.read(bb);
  }

  // ---
  // Time of last access to this value.
  transient long _lastAccessedTime = System.currentTimeMillis();
  public final void touch() {_lastAccessedTime = System.currentTimeMillis();}


  // ---
  // A Value is persisted. The Key is used to define the filename.
  public transient Key _key;

  // Assertion check that Keys match, for those Values that require an internal
  // Key (usually for disk filename persistence).
  protected boolean isSameKey(Key key) { return (_key==null) || (_key == key); }

  // ---
  // Backend persistence info.  3 bits are reserved for 8 different flavors of
  // backend storage.  1 bit for whether or not the latest _mem field is
  // entirely persisted on the backend storage, or not.  Note that with only 1
  // bit here there is an unclosable datarace: one thread could be trying to
  // change _mem (e.g. to null for deletion) while another is trying to write
  // the existing _mem to disk (for persistence).  This datarace only happens
  // if we have racing deletes of an existing key, along with racing persist
  // attempts.  There are other races that are stopped higher up the stack: we
  // do not attempt to write to disk, unless we have *all* of a Value, so
  // extending _mem (from a remote read) should not conflict with writing _mem
  // to disk.
  //
  // The low 3 bits are final.
  // The on/off disk bit is strictly cleared by the higher layers (e.g. Value.java)
  // and strictly set by the persistence layers (e.g. PersistIce.java).
  public volatile byte _persist; // 3 bits of backend flavor; 1 bit of disk/notdisk
  public final static byte ICE = 1<<0; // ICE: distributed local disks
  public final static byte HDFS= 2<<0; // HDFS: backed by hadoop cluster
  public final static byte S3  = 3<<0; // Amazon S3
  public final static byte NFS = 4<<0; // NFS: Standard file system
  public final static byte BACKEND_MASK = (8-1);
  public final static byte NOTdsk = 0<<3; // latest _mem is persisted or not
  public final static byte ON_dsk = 1<<3;
  final public void clrdsk() { _persist &= ~ON_dsk; } // note: not atomic
  final public void setdsk() { _persist |=  ON_dsk; } // note: not atomic
  final public boolean isPersisted() { return (_persist&ON_dsk)!=0; }

  // ---
  // Interface for using the persistence layer(s).
  public boolean onICE (){ return (_persist & BACKEND_MASK) ==  ICE; }
  public boolean onHDFS(){ return (_persist & BACKEND_MASK) == HDFS; }
  public boolean onNFS (){ return (_persist & BACKEND_MASK) ==  NFS; }
  public boolean onS3  (){ return (_persist & BACKEND_MASK) ==   S3; }

  /** Store complete Values to disk */
  void storePersist() {
    if( isPersisted() ) return;
    switch( _persist&BACKEND_MASK ) {
    case ICE : PersistIce .fileStore(this); break;
    case HDFS: PersistHdfs.fileStore(this); break;
    case NFS : PersistNFS .fileStore(this); break;
    case S3  : PersistS3  .fileStore(this); break;
    default  : throw H2O.unimpl();
    }
  }

  /** Remove dead Values from disk */
  void removeIce() {
    // do not yank memory, as we could have a racing get hold on to this
    //  free_mem();
    if( !isPersisted() || !onICE() ) return; // Never hit disk?
    clrdsk();  // Not persisted now
    PersistIce.fileDelete(this);
  }
  /** Load some or all of completely persisted Values */
  byte[] loadPersist() {
    assert isPersisted();
    switch( _persist&BACKEND_MASK ) {
    case ICE : return PersistIce .fileLoad(this);
    case HDFS: return PersistHdfs.fileLoad(this);
    case NFS : return PersistNFS .fileLoad(this);
    case S3  : return PersistS3  .fileLoad(this);
    default  : throw H2O.unimpl();
    }
  }

  public String nameOfPersist() {
    switch( _persist&BACKEND_MASK ) {
    case ICE : return "ICE";
    case HDFS: return "HDFS";
    case S3  : return "S3";
    case NFS : return "NFS";
    default  : throw H2O.unimpl();
    }
  }

  /** Set persistence to HDFS from ICE */
  public void setHdfs() {
    assert onICE();
    byte[] mem = memOrLoad();    // Get into stable memory
    removeIce();           // Remove from ICE disk
    _persist = Value.HDFS|Value.NOTdsk;
    assert onHDFS();       // Flip to HDFS
    _mem = mem; // Close a race with the H2O cleaner zapping _mem while removing from ice
    storePersist();        // Store back to HDFS
  }


  /** Lazily manifest data chunks on demand.  Requires a pre-existing ValueArray.
   * Probably should be moved into HDFS-land, except that the same logic applies
   * to all stores providing large-file access by default including S3. */
  public static Value lazyArrayChunk( Key key ) {
    if( key._kb[0] != Key.ARRAYLET_CHUNK ) return null; // Not an arraylet chunk
    Key arykey = ValueArray.getArrayKey(key);
    Value v1 = DKV.get(arykey);
    if( v1 == null ) return null;       // Nope; not there
    if( !v1.isArray() ) return null; // Or not a ValueArray
    switch( v1._persist&BACKEND_MASK ) {
    case ICE : if( !key.home() ) return null; // Only do this on the home node for ICE
               return PersistIce .lazyArrayChunk(key);
    case HDFS: return PersistHdfs.lazyArrayChunk(key);
    case NFS : return PersistNFS .lazyArrayChunk(key);
    case S3  : return PersistS3  .lazyArrayChunk(key);
    default  : throw H2O.unimpl();
    }
  }

  public StringBuilder getString( int len, StringBuilder sb ) {
    int newlines=0;
    byte[] b = memOrLoad();
    final int LEN=Math.min(len,b.length);
    for( int i=0; i<LEN; i++ ) {
      byte c = b[i];
      if( c == '&' ) sb.append("&amp;");
      else if( c == '<' ) sb.append("&lt;");
      else if( c == '>' ) sb.append("&gt;");
      else if( c == '\n' ) { sb.append("<br>"); if( newlines++ > 5 ) break; }
      else if( c == ',' && i+1<LEN && b[i+1]!=' ' )
        sb.append(", ");
      else sb.append((char)c);
    }
    if( b.length > LEN ) sb.append("...");
    return sb;
  }

  // Expand a KEY_OF_KEYS into an array of keys
  public Key[] flatten() {
    assert _key._kb[0] == Key.KEY_OF_KEYS;
    return new AutoBuffer(memOrLoad(), 0).getA(Key.class);
  }

  // Get the 1st bytes from either a plain Value, or chunk 0 of a ValueArray
  public byte[] getFirstBytes() {
    return (!isArray() ? this : DKV.get(ValueArray.getChunkKey(0,_key))).memOrLoad();
  }

  // For plain Values, just the length in bytes.
  // For ValueArrays, the length of all chunks.
  public long length() {
    if(!isArray()) return _max;
    return ValueArray.value(this).length();
  }


  // --------------------------------------------------------------------------
  // Set just the initial fields
  public Value(Key k, int max, byte[] mem, byte be, byte type ) {
    assert mem==null || mem.length==max;
    assert max < MAX : "Value size=0x"+Integer.toHexString(max);
    _key = k;
    _max = max;
    _mem = mem;
    // For the ICE backend, assume new values are not-yet-written.
    // For HDFS & NFS backends, assume we from global data and preserve the
    // passed-in persist bits
    byte p = (byte)(be&BACKEND_MASK);
    _persist = (p==ICE) ? p : be;
    _type = type;
  }
  public Value(Key k, int max, byte be    ) { this(k, max, null, be, FREEZABLE); }
  public Value(Key k, int max             ) { this(k, max, MemoryManager.malloc1(max), ICE, FREEZABLE); }
  public Value(Key k, int max, byte[] mem ) { this(k, max, mem, ICE, FREEZABLE); }
  public Value(Key k, String s            ) { this(k, s.length(), s.getBytes()); }
  public Value(Key k, byte[] bits         ) { this(k, bits.length,bits); }
  public Value(Key k, byte[] bits, byte persist, byte type) { this(k, bits.length,bits,persist, type); }
  public Value() { }            // for auto-serialization

  // Custom serializers: the _mem field is racily cleared by the MemoryManager
  // and the normal serializer then might ship over a null instead of the
  // intended byte[].  Also, the value is NOT on the deserialize'd machines disk
  public AutoBuffer write(AutoBuffer bb) {
    byte p = _persist;
    if( onICE() ) p &= ~ON_dsk; // Not on the remote disk
    return bb.put1(p).put1(_type).putA1(memOrLoad());
  }

  // Custom serializer: set _max from _mem length; set replicas & timestamp.
  public Value read(AutoBuffer bb) {
    assert _key == null;        // Not set yet
    _persist = (byte) bb.get1();
    _type = (byte) bb.get1();
    _mem = bb.getA1();
    _max = _mem.length;
    // On remote nodes _replicas is initialized to 0 (signaling a remote PUT is
    // in progress) flips to -1 when the remote PUT is done, or +1 if a notify
    // needs to happen.
    _replicas.set(-1);          // Set as 'remote put is done'
    touch();
    return this;
  }

  // ---------------------
  // Ordering of K/V's!  This field tracks a bunch of things used in ordering
  // updates to the same Key.  Ordering Rules:
  // - Program Order.  You see your own writes.  All writes in a single thread
  //   strongly ordered (writes never roll back).  In particular can:
  //   PUT(v1), GET, PUT(null) and The Right Thing happens.
  // - Unrelated writes can race (unless fencing).
  // - Writes are not atomic: some people can see a write ahead of others.
  // - Last-write-wins: if we do a zillion writes to the same Key then wait "a
  //   long time", then do reads all reads will see the same last value.
  // - Blocking on a PUT stalls until the PUT is cloud-wide visible
  //
  // For comparison to H2O get/put MM
  // IA Memory Ordering,  8 principles from Rich Hudson, Intel
  // 1. Loads are not reordered with other loads
  // 2. Stores are not reordered with other stores
  // 3. Stores are not reordered with older loads
  // 4. Loads may be reordered with older stores to different locations but not
  //    with older stores to the same location
  // 5. In a multiprocessor system, memory ordering obeys causality (memory
  //    ordering respects transitive visibility).
  // 6. In a multiprocessor system, stores to the same location have a total order
  // 7. In a multiprocessor system, locked instructions have a total order
  // 8. Loads and stores are not reordered with locked instructions.
  //
  // My (KN, CNC) interpretation of H2O MM from today:
  // 1. Gets are not reordered with other Gets
  // 2  Puts may be reordered with Puts to different Keys.
  // 3. Puts may be reordered with older Gets to different Keys, but not with
  //    older Gets to the same Key.
  // 4. Gets may be reordered with older Puts to different Keys but not with
  //    older Puts to the same Key.
  // 5. Get/Put amongst threads doesn't obey causality
  // 6. Puts to the same Key have a total order.
  // 7. no such thing. although RMW operation exists with Put-like constraints.
  // 8. Gets and Puts may be reordered with RMW operations
  // 9. A write barrier exists that creates Sequential Consistency.  Same-key
  //    ordering (3-4) can't be used to create the effect.
  //
  // A Reader/Writer lock for the home node to control racing Gets and Puts.
  // - A bitvector of up to 58 cloud nodes known to have replicas
  // - 6 bits of active Gets (reader-lock count up to 58), or -1/63- locked
  // Active Readers/Gets atomically set the r/w lock count AND set their
  // replication-bit (or fail because the lock is write-locked, in which
  // case they Get is retried from the start and should see a new Value).
  //
  // An ACK from the client GET lowers the r/w lock count.
  //
  // Home node PUTs alter which Value is mapped to a Key, then they block until
  // there are no active GETs, then atomically set the write-lock, then send
  // out invalidates to all the replicas.  PUTs return when all invalidates
  // have reported back.
  //
  // An initial remote PUT will default the value to 0.  A 2nd PUT attempt will
  // block until the 1st one completes (multiple writes to the same Key from
  // the same JVM block, so there is at most 1 outstanding write to the same
  // Key from the same JVM).  The 2nd PUT will CAS the value to 1, indicating
  // the need for the finishing 1st PUT to call notify().
  //
  // Note that this sequence involves a lot of blocking on the writes, but not
  // the readers - i.e., writes are slow to complete.
  private transient final AtomicLong _replicas = new AtomicLong(0);
  public int numReplicas() {
    long r = _replicas.get();
    int c = 0;
    for( int i = 0; i < 58; ++i ) c += (r >> i) & 0x01;
    return c;
  }

  /** True if h2o has a copy of this Value */
  boolean isReplicatedTo( H2ONode h2o ) {
    assert h2o._unique_idx<58;
    return (_replicas.get()&(1L<<h2o._unique_idx)) != 0;
  }

  private static int decodeReaderCount(long replicas) { return (int) (replicas >>> 58); }
  private static long encodeReaderCount(int readers)  { return ((long)readers) << 58; }

  /** Atomically insert h2o into the replica list; reports false if the Value
   * flagged against future replication with a -1/63.  Also bumps the active
   * Get count, which remains until the Get completes (we recieve an ACKACK). */
  boolean setReplica( H2ONode h2o ) {
    assert h2o._unique_idx<58;
    assert _key.home(); // Only the HOME node for a key tracks replicas
    assert h2o != H2O.SELF;     // Do not track self as a replica
    while( true ) {     // Repeat, in case racing GETs are bumping the counter
      long old = _replicas.get();
      if( old == -1 ) return false; // No new replications
      long nnn = old + encodeReaderCount(1);
      nnn |= (1L<<h2o._unique_idx); // Set replica bit for H2O
      assert decodeReaderCount(nnn) < 58; // Count does not overflow
      assert decodeReaderCount(nnn) > 0;  // At least one reader now
      if( _replicas.compareAndSet(old,nnn) ) return true;
    }
  }

  /** Atomically lower active GET countn */
  void lowerActiveGetCount( H2ONode h2o ) {
    assert h2o._unique_idx<58;
    assert _key.home();  // Only the HOME node for a key tracks replicas
    assert h2o != H2O.SELF;     // Do not track self as a replica
    long nnn;
    while( true ) {     // Repeat, in case racing GETs are bumping the counter
      long old = _replicas.get();
      assert old != -1;             // Not locked yet, because we are active
      assert (old&(1L<<h2o._unique_idx)) !=0; // Self-bit is set
      assert decodeReaderCount(old) > 0; // Since lowering, must be at least 1
      nnn = old - encodeReaderCount(1);
      assert decodeReaderCount(nnn) >= 0; // Count does not go negative
      if( _replicas.compareAndSet(old,nnn) )
        break;                  // Repeat until count is lowered
    }
    if( decodeReaderCount(nnn) == 0 ) // GET count fell to zero?
      synchronized( this ) { notifyAll(); } // Notify any pending blocked PUTs
  }

  /** Atomically set the replica count to -1/63 locking it from further GETs and
   * ship out invalidates to caching replicas.  May need to block on active
   * GETs.  Updates a set of Future invalidates that can be blocked against. */
  Futures lockAndInvalidate( H2ONode sender, Futures fs ) {
    assert _key.home(); // Only the HOME node for a key tracks replicas
    // Lock against further GETs
    long old = _replicas.get();
    assert old != -1; // Only the thread doing a PUT ever locks
    assert decodeReaderCount(old) >= 0; // Count does not go negative
    // Repeat, in case racing GETs are bumping the counter
    while( decodeReaderCount(old) > 0 || // Has readers?
           !_replicas.compareAndSet(old,-1) ) { // or failed to lock?
      try { ForkJoinPool.managedBlock(this); } catch( InterruptedException e ) { }
      old = _replicas.get();
      assert old != -1; // Only the thread doing a PUT ever locks
      assert decodeReaderCount(old) >= 0; // Count does not go negative
    }
    assert decodeReaderCount(old) == 0; // Only get here with no active readers
    if( old == 0 ) return fs; // Nobody is caching, so nothing to block against

    // We have the set of Nodes with replicas now.  Ship out invalidates.
    for( int i=0; i<58; i++ )
      if( ((old>>i)&1) != 0 && H2ONode.IDX[i] != sender )
        TaskPutKey.invalidate(H2ONode.IDX[i],_key,fs);
    return fs;
  }

  /** Initialize the _replicas field for a PUT.  On the Home node (for remote
   * PUTs), it is initialized to the one replica we know about. */
  void initReplicaHome( H2ONode h2o, Key key ) {
    assert key.home();
    assert _key == null; // This is THE initializing key write for serialized Values
    assert h2o != H2O.SELF;     // Do not track self as a replica
    _key = key;
    // Set the replica bit for the one node we know about, and leave the
    // rest clear.  No GETs are in-flight at this time.
    _replicas.set(1L<<h2o._unique_idx);
  }

  /** Block this thread until all prior remote PUTs complete - to force
   * remote-PUT ordering on the home node. */
  void startRemotePut() {
    assert !_key.home();
    long x = 0;
    while( (x=_replicas.get()) != -1L ) // Spin until replicas==-1
      if( x == 1L || _replicas.compareAndSet(0L,1L) )
        try { ForkJoinPool.managedBlock(this); } catch( InterruptedException e ) { }
  }

  /** The PUT for this Value has completed.  Wakeup any blocked later PUTs. */
  void completeRemotePut() {
    assert !_key.home();
    // Attempt an eager blind attempt, assuming no blocked pending notifies
    if( _replicas.compareAndSet(0L, -1L) ) return;
    synchronized(this) {
      boolean res = _replicas.compareAndSet(1L, -1L);
      assert res;               // Must succeed
      notifyAll();              // Wake up pending blocked PUTs
    }
  }

  public boolean isRemotePutInFlight() {
    assert !_key.home();
    return _replicas.get() != -1;
  }

  public boolean isHex() {
    if( !isArray() ) return false;
    ValueArray va = ValueArray.value(this);
    if( va._cols == null || va._cols.length == 0 ) return false;
    if( va._cols.length > 1 ) return true;
    if( va._cols[0]._size != 1 ) return true;
    return _key.toString().endsWith(".hex");
  }

  /** Return true if blocking is unnecessary.
   * Alas, used in TWO places and the blocking API forces them to share here. */
  @Override public boolean isReleasable() {
    long r = _replicas.get();
    if( _key.home() ) {         // Called from lock_and_invalidate
      // Home-key blocking: wait for active-GET count to fall to zero
      return decodeReaderCount(r) == 0;
    } else {                    // Called from start_put
      // Remote-key blocking: wait for active-PUT lock to hit -1
      assert r == 1 || r == -1; // Either waiting (1) or done (-1) but not started(0)
      return r == -1;           // done!
    }
  }
  /** Possibly blocks the current thread.  Returns true if isReleasable would
   * return true.  Used by the FJ Pool management to spawn threads to prevent
   * deadlock is otherwise all threads would block on waits. */
  @Override public synchronized boolean block() {
    while( !isReleasable() ) { try { wait(); } catch( InterruptedException e ) { } }
    return true;
  }

  /** Creates a Stream for reading bytes */
  public InputStream openStream() throws IOException {
    if( !isArray() ) return new ByteArrayInputStream(memOrLoad());
    return ValueArray.value(this).openStream();
  }
}
