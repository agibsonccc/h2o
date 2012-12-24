# cheatsheet for current GLM json return hierachy
# h2o
# key
# GLMModel
#     GLMParams
#         betaEps
#         caseVal 
#         family
#         link
#         maxIter
#         threshold
#         weight
#     LSMParams
#         penalty
#     coefficients
#         <col>
#         Intercept
#     dataset
#     isDone
#     iterations
#     time
#     validations
#         cm
#         dataset
#         dof
#         err
#         nrows
#         nullDev
#         resDev
# xval
#     is a list of things that match GLMModel?

# params on family=gaussian? No xval?
# conditional set depends on family=
# {
# "GLMParams": {
# "betaEps": 0.0001, 
# "family": "gaussian", 
# "link": "identity", 
# "maxIter": 50
# }, 
# "LSMParams": {
# "lambda": 1e-08, 
# "penalty": "L2"
# }, 

def simpleCheckGLM(self,glm,colX, **kwargs):
    if 'warnings' in glm:
        print "\nwarnings:", glm['warnings']

    # h2o GLM will verboseprint the result and print errors. 
    # so don't have to do that
    # different when xvalidation is used? No trainingErrorDetails?
    GLMModel = glm['GLMModel']
    print "GLM time", GLMModel['time']

    GLMParams = GLMModel["GLMParams"]
    family = GLMParams["family"]

    iterations = GLMModel['iterations']
    print "\nGLMModel/iterations:", iterations

    # pop the first validation from the list
    validationsList = GLMModel['validations']
    validations = validationsList.pop()
    print "\nGLMModel/validations/err:", validations['err']

    if (not family in kwargs) or kwargs['family']=='poisson' or kwargs['family']=="gaussian":
        # FIX! xval not in gaussian or poisson?
        pass
    else:
        if ('xval' in kwargs):
            # no cm in poisson?
            cmList = validations['cm']

            xvalList = glm['xval']
            xval = xvalList.pop()
            # FIX! why is this returned as a list? no reason?
            validationsList = xval['validations']
            validations = validationsList.pop()
            print "\nxval/../validations/err:", validations['err']

    # it's a dictionary!
    coefficients = GLMModel['coefficients']
    print "\ncoefficients:", coefficients
    # pick out the coefficent for the column we enabled.
    absXCoeff = abs(float(coefficients[str(colX)]))
    # intercept is buried in there too
    absIntercept = abs(float(coefficients['Intercept']))

    self.assertGreater(absXCoeff, 1e-18, (
        "abs. value of GLM coefficients['" + str(colX) + "'] is " +
        str(absXCoeff) + ", not >= 1e-18 for X=" + str(colX)
        ))

    self.assertGreater(absIntercept, 1e-18, (
        "abs. value of GLM coefficients['Intercept'] is " +
        str(absIntercept) + ", not >= 1e-18 for X=" + str(colX)
                ))

    # many of the GLM tests aren't single column though.

    # quick and dirty check: if all the coefficients are zero, 
    # something is broken
    # intercept is in there too, but this will get it okay
    # just sum the abs value  up..look for greater than 0
    s = 0.0
    for c in coefficients:
        v = coefficients[c]
        s += abs(float(v))
        self.assertGreater(s, 1e-18, (
            "sum of abs. value of GLM coefficients/intercept is " + str(s) + ", not >= 1e-18"
            ))


# compare this glm to last one. since the files are concatenations, 
# the results should be similar? 10% of first is allowed delta
def compareToFirstGlm(self, key, glm, firstglm):
    delta = .1 * float(firstglm[key])
    msg = "Too large a delta (" + str(delta) + ") comparing current and first for: " + key
    self.assertAlmostEqual(float(glm[key]), float(firstglm[key]), delta=delta, msg=msg)
    self.assertGreaterEqual(float(glm[key]), 0.0, key + " not >= 0.0 in current")


