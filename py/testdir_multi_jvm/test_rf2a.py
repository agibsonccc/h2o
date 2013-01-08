import os, json, unittest, time, shutil, sys
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd

class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        h2o.build_cloud(node_count=3)

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_RFhhp(self):
        csvPathnamegz = h2o.find_file('smalldata/hhp.cut3.214.data.gz')
        print "RF start on ", csvPathnamegz, "this will probably take a minute.."
        start = time.time()
        h2o_cmd.runRF(csvPathname=csvPathnamegz, trees=23,
                timeoutSecs=60, retryDelaySecs=10)
        print "RF end on ", csvPathnamegz, 'took', time.time() - start, 'seconds'

if __name__ == '__main__':
    h2o.unit_main()