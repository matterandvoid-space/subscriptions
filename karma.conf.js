module.exports = function (config) {
  var root = 'target' // same as :output-dir in shadow-cljs.edn
  config.set({
    frameworks: ['cljs-test'],
    browsers: ['ChromeHeadless'],
    basePath: './',
    files: [
      root + '/ci.js'
    ],
    plugins: [
        'karma-cljs-test',
        'karma-chrome-launcher',
        // 'karma-junit-reporter'
    ],
    colors: true,
    logLevel: config.LOG_INFO,

    client: {
      args: ['shadow.test.karma.init'],
      singleRun: true
    },

    // the default configuration
    // junitReporter: {
    //   outputDir: junitOutputDir + '/karma', // results will be saved as outputDir/browserName.xml
    //   outputFile: undefined, // if included, results will be saved as outputDir/browserName/outputFile
    //   suite: '' // suite will become the package name attribute in xml testsuite element
    // }
  })
}
