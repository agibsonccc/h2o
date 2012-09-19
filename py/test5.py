import os, json, unittest, time, shutil, sys
import util.h2o as h2o

def putKey(n,value,key=None,repl=None):
    put = n.put_key(value,key,repl)
    parseKey = n.parse(put['keyHref'])

    ### print 'After put, parseKey:', parseKey
    ## print 'key', parseKey['key']
    ## print 'keyHref', parseKey['keyHref']
    ## print 'put TimeMS', parseKey['TimeMS']

    # ?? how we we check that the put completed okay?
    # FIX! temp hack to avoid races? for a RF that follows?
    time.sleep(0.2) 
    return parseKey

# a key gets generated afte a put.
def putFile(n,csvPathname,key=None,repl=None):
    put = n.put_file(csvPathname,key,repl)
    parseKey = n.parse(put['keyHref'])

    ### print 'After put, parseKey:', parseKey
    ## print 'key', parseKey['key']
    ## print 'keyHref', parseKey['keyHref']
    ## print 'put TimeMS', parseKey['TimeMS']

    # ?? how we we check that the put completed okay?
    # FIX! temp hack to avoid races? for a RF that follows?
    time.sleep(0.2) 
    return parseKey

def getFile(n,csvPathname):
    put = n.get_file(csvPathname)
    parseKey = n.parse(put['keyHref'])

    print 'After get, parseKey:', parseKey
    ## print 'key', parseKey['key']
    ## print 'keyHref', parseKey['keyHref']
    ## print 'put TimeMS', parseKey['TimeMS']

    # ?? how we we check that the put completed okay?
    # FIX! temp hack to avoid races? for a RF that follows?
    time.sleep(0.2) 
    return parseKey

# we pass the key from the put, for knowing what to RF on.
def runRFonly(n,parseKey,trees,depth,timeoutSecs):
    # FIX! what is rf set to here (on pass/fail?)
    rf = n.random_forest(parseKey['keyHref'],trees,depth)
    ### print 'After random_forest, rf:', rf
    ## print 'confKey', rf['confKey']
    ## print 'confKeyHref', rf['confKeyHref']

    # has the ip address for the cloud
    ## print 'h2o', rf['h2o']
    ## print 'depth', rf['depth'] # depth seems to change correctly now?

    # this expects the response to match the number of trees you told it to do
    n.stabilize('random forest finishing', timeoutSecs,
        lambda n: n.random_forest_view(rf['confKeyHref'])['got'] == trees)

class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        h2o.clean_sandbox()
        global nodes
        print "Just doing one node in the cloud for this test"
        nodes = h2o.build_cloud(node_count=1)

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud(nodes)

    def setUp(self):
        pass

    def tearDown(self):
        pass

    # NOTE: unittest will run tests in an arbitrary order..not constrained to order here.

    # Possible hack: change the names so this test order matches alphabetical order
    # by using intermediate "_A_" etc. 
    # That should make unittest order match order here? 

    def test_Basic(self):
        for n in nodes:
            c = n.get_cloud()
            self.assertEqual(c['cloud_size'], len(nodes), 'inconsistent cloud size')

    def test_GenParity1(self):
        global SYNDATASETS_DIR
        global SYNSCRIPTS_DIR

        SYNDATASETS_DIR = './syn_datasets'
        if os.path.exists(SYNDATASETS_DIR):
            shutil.rmtree(SYNDATASETS_DIR)
        os.mkdir(SYNDATASETS_DIR)

        SYNSCRIPTS_DIR = './syn_scripts'

        # always match the run below!
        # FIX! 1 row fails in H2O. skip for now
        for x in [10000]:
            # Have to split the string out to list for pipe
            shCmdString = SYNSCRIPTS_DIR + "/parity.pl 128 4 "+ str(x) + " quad"
            h2o.spawn_cmd('parity.pl', shCmdString.split())
            # the algorithm for creating the path and filename is hardwired in parity.pl..i.e
            csvFilename = "parity_128_4_" + str(x) + "_quad.data"  

        # wait to make sure the last file is done, in case we use the last file right away below
        # this is error prone because of variation in above?
        time.sleep(0.5) 

        # FIX! I suppose we should vary the number of trees to make sure the response changes
        # maybe just inc in loop
        trees = 137 # 200ms?
        depth = 45
        # bump this up too if you do?
        timeoutSecs = 10

        # always match the gen above!
        trial = 1

        print "This currently passes in python with 3 node"
        print "fails with one node startup and browser-controlled put"
        for x in xrange (10000,20000,50):
            sys.stdout.write('.')
            sys.stdout.flush()

            # just use one file for now
            ### csvFilename = "parity_128_4_" + str(10000) + "_quad.data"  
            ### csvPathname = SYNDATASETS_DIR + '/' + csvFilename
            csvPathname = "../smalldata/hhp_107_01_short.data"

            # FIX! TBD do we always have to kick off the run from node 0?

            # broke out the put separately so we can iterate a test just on the RF
            # FIX! put times are inaccurate as they report before the parse is actually finished
            # means we need fixed delay after the parse before we use it's results
            # that's embedded currently in putFile
            parseKey = putFile(nodes[0],csvPathname)

            ## parseKey = putKey(nodes[0],"HiJoe",key=None,repl=None)

            print 'Trial:', trial
            ### print 'put TimeMS:', parseKey['TimeMS']

            ### runRFonly(nodes[0],parseKey,trees,depth,timeoutSecs)

            # don't change tree count yet
            ## trees += 10
            ### timeoutSecs += 2
            trial += 1

            # FIX! do we need or want a random delay here?
            # is this because we're not sure if RF really completed?
            time.sleep(0.5) 


if __name__ == '__main__':
    unittest.main()