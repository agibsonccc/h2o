package water;
import java.util.concurrent.Future;

/**
 * Push the given key to the remote node
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class TaskPutKey extends DTask<TaskPutKey> {
  Key _key;
  Value _val;
  transient Value _xval;
  static void put( H2ONode h2o, Key key, Value val, Futures fs ) {
    Future f = RPC.call(h2o,new TaskPutKey(key,val));
    if( fs != null ) fs.add(f);
  }

  static void invalidate( H2ONode h2o, Key key, Futures fs ) { put(h2o,key,null,fs); }

  private TaskPutKey( Key key, Value val ) { _key = key; _xval = _val = val; }
  TaskPutKey() {}

  public TaskPutKey invoke( H2ONode sender ) {
    assert _key.home() || _val==null; // Only PUT to home for keys, or remote invalidation from home
    // Initialize Value for having a single known replica (the sender)
    if( _val != null ) _val.initReplicaHome(sender,_key);

    // Spin, until we update something.
    Value old = H2O.get(_key);
    while( H2O.putIfMatch(_key,_val,old) != old )
      old = H2O.get(_key);       // Repeat until we update something.
    // Invalidate remote caches.  Block, so that all invalidates are done
    // before we return to the remote caller.
    if( _key.home() && old != null )
      old.lockAndInvalidate(sender,new Futures()).blockForPending();
    // No return result
    _key = null;
    _val = null;
    return this;
  }
  @Override public void compute() { throw H2O.unimpl(); }

  // Received an ACK
  @Override public void onAck() {
    if( _xval != null ) _xval.completeRemotePut();
  }
}
