package com.shaft.listeners;

import com.shaft.driver.SHAFT;
import com.shaft.gui.internal.image.ImageProcessingActions;
import com.shaft.listeners.internal.JiraHelper;
import com.shaft.listeners.internal.RetryAnalyzer;
import com.shaft.listeners.internal.TestNGListenerHelper;
import com.shaft.properties.internal.PropertiesHelper;
import com.shaft.tools.internal.security.GoogleTink;
import com.shaft.tools.io.internal.ExecutionSummaryReport;
import com.shaft.tools.io.internal.IssueReporter;
import com.shaft.tools.io.internal.ProjectStructureManager;
import com.shaft.tools.io.internal.ReportManagerHelper;
import io.qameta.allure.Allure;
import lombok.Getter;
import org.testng.*;
import org.testng.annotations.ITestAnnotation;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class TestNGListener implements IAlterSuiteListener, IAnnotationTransformer,
        IExecutionListener, ISuiteListener, IInvokedMethodListener, ITestListener {

    private static final List<ITestNGMethod> passedTests = new ArrayList<>();
    private static final List<ITestNGMethod> failedTests = new ArrayList<>();
    private static final List<ITestNGMethod> skippedTests = new ArrayList<>();

    private static long executionStartTime;

    @Getter
    private static XmlTest xmlTest;

    private static boolean isJunitRunBool = false;
    private static boolean isTestNGRunBool = false;

    public static boolean isTestNGRun() {
        if (!isTestNGRunBool && !isJunitRunBool) {
            identifyRunType();
        }
        return isTestNGRunBool;
    }

    public static boolean isJunitRun() {
        if (!isTestNGRunBool && !isJunitRunBool) {
            identifyRunType();
        }
        return isJunitRunBool;
    }

    private static void identifyRunType() {
        Supplier<Stream<?>> stacktraceSupplier = () -> Arrays.stream((new Throwable()).getStackTrace()).map(StackTraceElement::getClassName);
        var isUsingJunitDiscovery = stacktraceSupplier.get().anyMatch(org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.class.getCanonicalName()::equals);
        var isUsingTestNG = stacktraceSupplier.get().anyMatch(TestNG.class.getCanonicalName()::equals);
        var isUsingCucumber = stacktraceSupplier.get().anyMatch(io.cucumber.core.runner.Runner.class.getCanonicalName()::equals);
        if (isUsingJunitDiscovery || isUsingTestNG) {
            System.out.println("TestNG run detected...");
            isTestNGRunBool = true;
        } else if (isUsingCucumber) {
            System.out.println("Cucumber run detected...");
            isTestNGRunBool = true;
        } else {
            System.out.println("JUnit5 run detected...");
            isJunitRunBool = true;
        }
    }

    public static void engineSetup() {
        ReportManagerHelper.setDiscreteLogging(true);
        PropertiesHelper.initialize();
        SHAFT.Properties.reporting.set().disableLogging(true);
        Allure.getLifecycle();
        Reporter.setEscapeHtml(false);
        if (isTestNGRun()) {
            ProjectStructureManager.initialize(ProjectStructureManager.Mode.TESTNG);
        } else {
            ProjectStructureManager.initialize(ProjectStructureManager.Mode.JUNIT);
        }
        TestNGListenerHelper.configureJVMProxy();
        GoogleTink.initialize();
        GoogleTink.decrypt();
        SHAFT.Properties.reporting.set().disableLogging(false);

        ReportManagerHelper.logEngineVersion();
        ImageProcessingActions.loadOpenCV();

        ReportManagerHelper.cleanExecutionSummaryReportDirectory();
        ReportManagerHelper.initializeAllureReportingEnvironment();
        ReportManagerHelper.initializeExtentReportingEnvironment();

        ReportManagerHelper.setDiscreteLogging(SHAFT.Properties.reporting.alwaysLogDiscreetly());
        ReportManagerHelper.setDebugMode(SHAFT.Properties.reporting.debugMode());
    }

    /**
     * gets invoked before TestNG proceeds with invoking any other listener.
     */
    @Override
    public void onExecutionStart() {
        engineSetup();
    }

    /**
     * Implementations of this interface will gain access to the {@link XmlSuite} object and thus let
     * users be able to alter a suite or a test based on their own needs.
     *
     * @param suites - The list of {@link XmlSuite}s that are part of the current execution.
     */
    @Override
    public void alter(List<XmlSuite> suites) {
        if (isTestNGRun()) {
            TestNGListenerHelper.configureTestNGProperties(suites);
            TestNGListenerHelper.updateDefaultSuiteAndTestNames(suites);
            TestNGListenerHelper.attachConfigurationHelperClass(suites);
            //All alterations should be finalized before duplicating the
            //test suites for cross browser execution
            TestNGListenerHelper.configureCrossBrowserExecution(suites);
        }
    }

    /**
     * This method is invoked before the SuiteRunner starts.
     *
     * @param suite The suite
     */
    @Override
    public void onStart(ISuite suite) {
        if (isTestNGRun()) {
            TestNGListenerHelper.setTotalNumberOfTests(suite);
            executionStartTime = System.currentTimeMillis();
        }
    }

    /**
     * This method will be invoked by TestNG to give you a chance to modify a TestNG annotation read
     * from your test classes. You can change the values you need by calling any of the setters on the
     * ITest interface.
     *
     * <p>Note that only one of the three parameters testClass, testConstructor and testMethod will be
     * non-null.
     *
     * @param annotation      The annotation that was read from your test class.
     * @param testClass       If the annotation was found on a class, this parameter represents this class
     *                        (null otherwise).
     * @param testConstructor If the annotation was found on a constructor, this parameter represents
     *                        this constructor (null otherwise).
     * @param testMethod      If the annotation was found on a method, this parameter represents this
     *                        method (null otherwise).
     */
    @Override
    public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
        if (isTestNGRun()) {
            annotation.setRetryAnalyzer(RetryAnalyzer.class);
        }
    }

    /**
     * A listener that gets invoked before a method is invoked by TestNG. This listener will
     * be invoked for configuration and test methods irrespective of whether they pass/fail or get
     * skipped. This listener invocation can be disabled for SKIPPED tests through one of the below
     * mechanisms:
     *
     * <ul>
     *   <li>Command line parameter <code>alwaysRunListeners</code>
     *   <li>Build tool
     *   <li>Via {@code TestNG.alwaysRunListeners(false)}
     * </ul>
     */
    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult iTestResult, ITestContext iTestContext) {
        if (isTestNGRun()) {
            xmlTest = method.getTestMethod().getXmlTest();
            JiraHelper.prepareTestResultAttributes(method, iTestResult);
            TestNGListenerHelper.setTestName(iTestContext);
            TestNGListenerHelper.logTestInformation(iTestResult);
            TestNGListenerHelper.failFast(iTestResult);
            TestNGListenerHelper.skipTestsWithLinkedIssues(iTestResult);
        }
    }

    /**
     * A listener that gets invoked after a method is invoked by TestNG. This listener will
     * be invoked for configuration and test methods irrespective of whether they pass/fail or get
     * skipped. This listener invocation can be disabled for SKIPPED tests through one of the below
     * mechanisms:
     *
     * <ul>
     *   <li>Command line parameter <code>alwaysRunListeners</code>
     *   <li>Build tool
     *   <li>Via {@code TestNG.alwaysRunListeners(false)}
     * </ul>
     */
    @Override
    public void afterInvocation(IInvokedMethod iInvokedMethod, ITestResult iTestResult, ITestContext iTestContext) {
        if (isTestNGRun()) {
            IssueReporter.updateTestStatusInCaseOfVerificationFailure(iTestResult);
            IssueReporter.updateIssuesLog(iTestResult);
            TestNGListenerHelper.updateConfigurationMethodLogs(iTestResult);
            ReportManagerHelper.setDiscreteLogging(SHAFT.Properties.reporting.alwaysLogDiscreetly());
        }
    }

    /**
     * gets invoked at the very last (after attachTestArtifacts generation phase), before TestNG exits the JVM.
     */
    @Override
    public void onExecutionFinish() {
        if (isTestNGRun()) {
            ReportManagerHelper.setDiscreteLogging(true);
            JiraHelper.reportExecutionStatusToJira();
            GoogleTink.encrypt();
            ReportManagerHelper.generateAllureReportArchive();
            ReportManagerHelper.openAllureReportAfterExecution();
            long executionEndTime = System.currentTimeMillis();
            ExecutionSummaryReport.generateExecutionSummaryReport(passedTests.size(), failedTests.size(), skippedTests.size(), executionStartTime, executionEndTime);
            ReportManagerHelper.logEngineClosure();
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        if (isTestNGRun()) {
            passedTests.add(result.getMethod());
            ExecutionSummaryReport.casesDetailsIncrement(result.getMethod().getQualifiedName().replace("." + result.getMethod().getMethodName(), ""),
                    result.getMethod().getMethodName(), result.getMethod().getDescription(), "",
                    ExecutionSummaryReport.StatusIcon.PASSED.getValue() + ExecutionSummaryReport.Status.PASSED.name(), TestNGListenerHelper.testHasIssueAnnotation(result));
        }
    }

    @Override
    public void onTestFailure(ITestResult result) {
        if (isTestNGRun()) {
            failedTests.add(result.getMethod());
            ExecutionSummaryReport.casesDetailsIncrement(result.getMethod().getQualifiedName().replace("." + result.getMethod().getMethodName(), ""),
                    result.getMethod().getMethodName(), result.getMethod().getDescription(), result.getThrowable().getMessage(),
                    ExecutionSummaryReport.StatusIcon.FAILED.getValue() + ExecutionSummaryReport.Status.FAILED.name(), TestNGListenerHelper.testHasIssueAnnotation(result));
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        if (isTestNGRun()) {
            skippedTests.add(result.getMethod());
            ExecutionSummaryReport.casesDetailsIncrement(result.getMethod().getQualifiedName().replace("." + result.getMethod().getMethodName(), ""),
                    result.getMethod().getMethodName(), result.getMethod().getDescription(), result.getThrowable().getMessage(),
                    ExecutionSummaryReport.StatusIcon.SKIPPED.getValue() + ExecutionSummaryReport.Status.SKIPPED.name(), TestNGListenerHelper.testHasIssueAnnotation(result));
        }
    }
}