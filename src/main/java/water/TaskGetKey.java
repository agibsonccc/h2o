package water;
import water.nbhm.NonBlockingHashMap;

/**
 * Get the given key from the remote node
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class TaskGetKey extends DTask<TaskGetKey> {
  Key _key;                  // Set by client/sender JVM, cleared by server JVM
  Value _val;                // Set by server JVM, read by client JVM
  transient Key _xkey;       // Set by client, read by client
  transient H2ONode _h2o;    // Set by server JVM, read by server JVM on ACKACK

  // Unify multiple Key/Value fetches for the same Key from the same Node at
  // the "same time".  Large key fetches are slow, and we'll get multiple
  // requests close in time.  Batch them up.
  public static final NonBlockingHashMap<Key,RPC> TGKS = new NonBlockingHashMap();

  // Get a value from a named remote node
  public static Value get( H2ONode target, Key key ) {
    RPC<TaskGetKey> rpc;
    while( true ) {       // Repeat until we get a unique TGK installed per key
      // Do we have an old TaskGetKey in-progress?
      rpc = TGKS.get(key);
      if( rpc != null ) break;
      // Make a new TGK.
      rpc = new RPC(target,new TaskGetKey(key));
      if( TGKS.putIfMatchUnlocked(key,rpc,null) == null ) {
        rpc.call();             // Start the op
        break;
      }
    }
    Value val = rpc.get()._val; // Block for, then fetch out the result
    TGKS.putIfMatchUnlocked(key,null,rpc); // Clear from cache
    return val;
  }

  private TaskGetKey( Key key ) { _key = _xkey = key; }
  TaskGetKey() {}

  // Top-level non-recursive invoke
  @Override public TaskGetKey invoke( H2ONode sender ) {
    _h2o = sender;
    Key k = _key;
    _key = null;          // Not part of the return result
    assert k.home();      // Gets are always from home (less we do replication)
    // Shipping a result?  Track replicas so we can invalidate.  There's a
    // narrow race on a moving K/V mapping tracking this Value just as it gets
    // deleted - in which case, simply retry for another Value.
    do  _val = H2O.get(k);      // The return result
    while( _val != null && !_val.setReplica(sender) );
    return this;
  }
  @Override public void compute() { throw H2O.unimpl(); }

  // Received an ACK; executes on the node asking&receiving the Value
  @Override public void onAck() {
    if( _val != null ) {        // Set transient fields after deserializing
      assert !_xkey.home() && _val._key == null;
      _val._key = _xkey;
    }
    // Now update the local store, caching the result.

    // We only started down the TGK path because we missed locally, so we only
    // expect to find a NULL in the local store.  If somebody else installed
    // another value (e.g. a racing TGK, or racing local Put) this value must
    // be more recent than our NULL - but is UNORDERED relative to the Value
    // returned from the Home.  We'll take the local Value to preserve ordering
    // and rely on invalidates from Home to force refreshes as needed.

    // Hence we can do a blind putIfMatch here over a null.
    // If it fails, what is there is also the TGK result.
    Value res = H2O.putIfMatch(_xkey,_val,null);
    if( res != null ) _val = res;
  }

  // Received an ACKACK; executes on the node sending the Value
  @Override public void onAckAck() {
    if( _val != null ) _val.lowerActiveGetCount(_h2o);
  }

  @Override public boolean isHighPriority() { return true; }
}
