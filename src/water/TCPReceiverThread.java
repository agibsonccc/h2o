package water;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The Thread that looks for TCP Cloud requests.
 *
 * This thread just spins on reading TCP requests from other Nodes.
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class TCPReceiverThread extends Thread {

  // The Run Method.

  // Started by main() on a single thread, this code manages reading TCP requests
  public void run() {
    ServerSocket sock = null, errsock = null;
    boolean saw_error = false;

    // Loop forever accepting Cloud Management requests
    while( true ) {
      Socket client=null;
      
      try { 
        // ---
        // Cleanup from any prior socket failures.  Rare unless we're really sick.
        if( errsock != null ) { // One time attempt a socket close
          final ServerSocket tmp2 = errsock; errsock = null;
          tmp2.close();       // Could throw, but errsock cleared for next pass
        }
        if( saw_error ) Thread.sleep(1000); // prevent deny-of-service endless socket-creates
        saw_error = false;

        // ---
        // More common-case setup of a ServerSocket
        if( sock == null )
          sock = new ServerSocket(H2O.TCP_PORT);

        // Open a TCP connection
        client = sock.accept();
        
        // Record the last time we heard from any given Node
        H2ONode hello = H2ONode.intern(client.getInetAddress(),client.getPort());
        hello._last_heard_from = System.currentTimeMillis();
        
        // Hand off the TCP connection to the proper handler
        DataInputStream dis = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        FJPacket.call((0xFF&dis.read()),dis,hello);
        // Write 1 byte of ACK
        OutputStream os = client.getOutputStream();
        os.write(99);           // Write 1 byte of ack

        client.close();

      } catch( Exception e ) {
        // On any error from anybody, close all sockets & re-open
        System.err.println("IO error on port "+H2O.TCP_PORT+": "+e); 
        e.printStackTrace();
        saw_error = true;
        errsock  = sock ;  sock  = null; // Signal error recovery on the next loop
      }
    }
  }
}
