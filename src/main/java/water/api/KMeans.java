package water.api;

import jsr166y.CountedCompleter;
import water.*;
import water.util.RString;

import com.google.gson.JsonObject;

public class KMeans extends Request {

  protected final H2OHexKey          _source  = new H2OHexKey(SOURCE_KEY);
  protected final Int                _k       = new Int(K);
  protected final Real               _epsilon = new Real(EPSILON, 1e-6);
  protected final HexAllColumnSelect _columns = new HexAllColumnSelect(COLS, _source);
  protected final H2OKey             _dest    = new H2OKey(DEST_KEY, (Key) null);

  @Override
  protected Response serve() {
    final ValueArray va = _source.value();
    final Key source = va._key;
    final int k = _k.value();
    final double epsilon = _epsilon.value();
    final int[] cols = _columns.value();
    Key dest = _dest.value();

    if( dest == null ) {
      String n = source.toString();
      int dot = n.lastIndexOf('.');
      if( dot > 0 )
        n = n.substring(0, dot);
      dest = Key.make(hex.KMeans.KMeansModel.KEY_PREFIX + n + Extensions.KMEANS);
    }

    final Job job = hex.KMeans.startJob(dest, va, k, epsilon, cols);
    try {
      H2O.FJP_NORM.submit(new CountedCompleter() {
        @Override
        public void compute() {
          hex.KMeans.run(job, va, k, epsilon, cols);
          tryComplete();
        }

        @Override
        public boolean onExceptionalCompletion(Throwable ex, CountedCompleter caller) {
          // TODO set job.error
          ex.printStackTrace();
          return true;
        }
      });

      JsonObject response = new JsonObject();
      response.addProperty(JOB, job.self().toString());
      response.addProperty(DEST_KEY, dest.toString());

      Response r = Progress.redirect(response, job.self(), dest);
      r.setBuilder(DEST_KEY, new KeyElementBuilder());
      return r;
    } catch( IllegalArgumentException e ) {
      return Response.error(e.getMessage());
    } catch( Error e ) {
      return Response.error(e.getMessage());
    }
  }

  // Make a link that lands on this page
  public static String link(Key k, String content) {
    RString rs = new RString("<a href='KMeans.query?%key_param=%$key'>%content</a>");
    rs.replace("key_param", SOURCE_KEY);
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }
}
