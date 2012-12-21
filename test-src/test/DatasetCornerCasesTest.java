package test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import hex.rf.DRF;
import hex.rf.Model;
import hex.rf.Tree.StatType;
import org.junit.BeforeClass;
import org.junit.Test;
import water.*;
import water.parser.ParseDataset;

public class DatasetCornerCasesTest extends KeyUtil {

  /*
   * HTWO-87 bug test
   *
   *  - two lines dataset (one line is a comment) throws assertion java.lang.AssertionError: classOf no dists > 0? 1
   */
  @Test public void testTwoLineDataset() throws Exception {
    Key fkey = KeyUtil.load_test_file("smalldata/test/HTWO-87-two-lines-dataset.csv");
    Key okey = Key.make("HTWO-87-two-lines-dataset.hex");
    ParseDataset.parse(okey,DKV.get(fkey));
    UKV.remove(fkey);
    ValueArray val = ValueArray.value(DKV.get(okey));

    // Check parsed dataset
    assertEquals("Number of chunks == 1", 1, val.chunks());
    assertEquals("Number of rows   == 2", 2, val._numrows);
    assertEquals("Number of cols   == 9", 9, val._cols.length);

    // setup default values for DRF
    int ntrees  = 5;
    int depth   = 30;
    int gini    = StatType.GINI.ordinal();
    long seed   =  42L;
    StatType statType = StatType.values()[gini];
    final int num_cols = val.numCols();
    final int classcol = num_cols-1; // For iris: classify the last column
    final int classes = (short)((val._cols[classcol]._max - val._cols[classcol]._min)+1);

    // Start the distributed Random Forest
    try {
      DRF drf = hex.rf.DRF.web_main(val,ntrees,depth,1.0f,(short)1024,statType,seed,classcol,new int[0], Key.make("model"),true,null,-1,false,null);
      // Just wait little bit
      drf.get();
      // Create incremental confusion matrix
      Model model = UKV.get(drf._modelKey,new Model());
      assertEquals("Number of classes == 1", 1,  model._classes);
      assertTrue("Number of trees > 0 ", model.size()> 0);
    } catch( DRF.IllegalDataException e ) {
      assertEquals("hex.rf.DRF$IllegalDataException: Number of classes must be >= 2 and <= 65534, found 1",e.toString());
    }
    UKV.remove(okey);
  }

  /* The following tests deal with one line dataset ended by different number of newlines. */

  /*
   * HTWO-87-related bug test
   *
   *  - only one line dataset - guessing parser should recognize it.
   *  - this datasets are ended by different number of \n (0x0A):
   *    - HTWO-87-one-line-dataset-0.csv    - the line is NOT ended by \n
   *    - HTWO-87-one-line-dataset-1.csv    - the line is ended by 1 \n     (0x0A)
   *    - HTWO-87-one-line-dataset-2.csv    - the line is ended by 2 \n     (0x0A 0x0A)
   *    - HTWO-87-one-line-dataset-1dos.csv - the line is ended by \r\n     (0x0D 0x0A)
   *    - HTWO-87-one-line-dataset-2dos.csv - the line is ended by 2 \r\n   (0x0D 0x0A 0x0D 0x0A)
   */
  @Test public void testOneLineDataset() {
    // max number of dataset files
    final String tests[] = {"0", "1unix", "2unix", "1dos", "2dos" };
    final String test_dir    = "smalldata/test/";
    final String test_prefix = "HTWO-87-one-line-dataset-";

    for (int i = 0; i < tests.length; i++) {
      String datasetFilename = test_dir + test_prefix + tests[i] + ".csv";
      String keyname     = test_prefix + tests[i] + ".hex";
      testOneLineDataset(datasetFilename, keyname);
    }
  }

  private void testOneLineDataset(String filename, String keyname) {
    Key fkey = KeyUtil.load_test_file(filename);
    Key okey = Key.make(keyname);
    ParseDataset.parse(okey,DKV.get(fkey));

    ValueArray val = ValueArray.value(DKV.get(okey));
    assertEquals(filename + ": number of chunks == 1", 1, val.chunks());
    assertEquals(filename + ": number of rows   == 2", 2, val._numrows);
    assertEquals(filename + ": number of cols   == 9", 9, val._cols.length);

    UKV.remove(fkey);
    UKV.remove(okey);
  }
}
