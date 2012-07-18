package water;
import java.net.DatagramPacket;

/**
 * A Paxos packet: an Accepted packet
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class UDPPaxosAccepted extends UDP {
  void call(DatagramPacket pack, H2ONode h2o) {
    Paxos.do_accepted(pack.getData(),h2o);
    // This is a stateless paxos-style packet; we must free it
    UDPReceiverThread.free_pack(pack);
  }

  // Build an Accepted packet.  It has the membership, plus proposal
  static void build_and_multicast( byte[] buf ) {
    buf[0] = (byte)UDP.udp.paxos_accepted.ordinal();
    MultiCast.multicast(buf);
  }

  // Pretty-print bytes 1-15; byte 0 is the udp_type enum
  // Since we are not recording the entire promise value, do not print it.
  public String print16( long lo, long hi ) { return ""; }
}
