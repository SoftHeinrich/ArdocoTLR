/* Licensed under MIT 2023-2024. */
package edu.kit.kastel.mcse.ardoco.tlr.tests.integration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import org.eclipse.collections.api.collection.ImmutableCollection;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.kit.kastel.mcse.ardoco.core.api.models.CodeModelType;
import edu.kit.kastel.mcse.ardoco.core.api.models.arcotl.Model;
import edu.kit.kastel.mcse.ardoco.core.api.output.ArDoCoResult;
import edu.kit.kastel.mcse.ardoco.core.common.RepositoryHandler;
import edu.kit.kastel.mcse.ardoco.core.common.util.DataRepositoryHelper;
import edu.kit.kastel.mcse.ardoco.core.common.util.TraceLinkUtilities;
import edu.kit.kastel.mcse.ardoco.core.execution.runner.ArDoCoRunner;
import edu.kit.kastel.mcse.ardoco.core.tests.TestUtil;
import edu.kit.kastel.mcse.ardoco.core.tests.eval.CodeProject;
import edu.kit.kastel.mcse.ardoco.core.tests.eval.GoldStandardProject;
import edu.kit.kastel.mcse.ardoco.core.tests.eval.results.EvaluationResults;
import edu.kit.kastel.mcse.ardoco.core.tests.eval.results.ExpectedResults;
import edu.kit.kastel.mcse.ardoco.metrics.ClassificationMetricsCalculator;

public abstract class TraceabilityLinkRecoveryEvaluation<T extends GoldStandardProject> {
    protected static final Logger logger = LoggerFactory.getLogger(TraceabilityLinkRecoveryEvaluation.class);
    private static final String WARNING_NO_CODE_MODEL = "Could not get code model to enroll gold standard. Using not enrolled gold standard!";
    // The path separator is to show that a code entry is not a class but rather a directory that ends with, currently, a "/" (unix style)
    // If the path separator in the gold standards are changed, this needs to update
    public static final String GOLD_STANDARD_PATH_SEPARATOR = "/";

    // This map can contain TLs from all of its subclasses.
    // Therefore, #resultHasRequiredData can be used to determine whether the result is valid for the specific subclass.
    protected static Map<GoldStandardProject, ArDoCoResult> resultMap = new LinkedHashMap<>();

    protected final ArDoCoResult runTraceLinkEvaluation(T project) {
        ArDoCoResult result = resultMap.get(project);
        if (result == null || !resultHasRequiredData(result)) {
            ArDoCoRunner runner = getAndSetupRunner(project);
            result = runner.run();
        }
        Assertions.assertNotNull(result);

        var goldStandard = getGoldStandard(project);
        goldStandard = enrollGoldStandard(goldStandard, result);
        var evaluationResults = calculateEvaluationResults(result, goldStandard);

        ExpectedResults expectedResults = getExpectedResults(project);
        TestUtil.logExtendedResultsWithExpected(logger, this, project.getProjectName(), evaluationResults, expectedResults);
        compareResults(evaluationResults, expectedResults);
        return result;
    }

    protected abstract boolean resultHasRequiredData(ArDoCoResult potentialResults);

    protected File getInputCode(CodeProject codeProject, boolean acmFile) {
        File inputCode;
        if (!acmFile) {
            prepareCode(codeProject);
            inputCode = codeProject.getCodeLocation(false);
        } else {
            inputCode = codeProject.getCodeLocation(true);
        }
        return inputCode;
    }

    protected abstract ArDoCoRunner getAndSetupRunner(T project);

    private void prepareCode(CodeProject codeProject) {
        File codeLocation = codeProject.getCodeLocation(false);

        if (!codeLocation.exists() || Objects.requireNonNull(codeLocation.listFiles()).length == 0) {
            RepositoryHandler.shallowCloneRepository(codeProject.getCodeRepository(), codeLocation.getAbsolutePath(), codeProject.getCommitHash());
        }
    }

    protected abstract ExpectedResults getExpectedResults(T project);

    protected abstract ImmutableList<String> getGoldStandard(T project);

    protected abstract ImmutableList<String> enrollGoldStandard(ImmutableList<String> goldStandard, ArDoCoResult result);

    protected static ImmutableList<String> enrollGoldStandardForCode(ImmutableList<String> goldStandard, ArDoCoResult result) {
        MutableList<String> enrolledGoldStandard = Lists.mutable.empty();

        Model codeModel;
        try {
            codeModel = DataRepositoryHelper.getModelStatesData(result.dataRepository()).getModel(CodeModelType.CODE_MODEL.getModelId());
            if (codeModel == null) {
                logger.warn(WARNING_NO_CODE_MODEL);
                return goldStandard;
            }
        } catch (NoSuchElementException e) {
            logger.warn(WARNING_NO_CODE_MODEL);
            return goldStandard;
        }

        for (String traceLink : goldStandard) {
            enrolledGoldStandard.addAll(enrollTraceLink(codeModel, traceLink));
        }

        return enrolledGoldStandard.toImmutable();
    }

    // This private method is currently only used to enroll the gold standard. If you decide to use it for enrolling something else, make sure that the assumptions hold
    // The main assumption currently is: Paths to directories end with a "/".
    private static List<String> enrollTraceLink(Model codeModel, String traceLink) {
        MutableList<String> enrolledTraceLink = Lists.mutable.empty();

        var splitTraceLink = traceLink.split(",");
        var codeEntry = splitTraceLink[1].strip();
        if (codeEntry.endsWith(GOLD_STANDARD_PATH_SEPARATOR)) {
            for (var endpoint : codeModel.getEndpoints()) {
                var endpointPath = endpoint.toString();
                if (endpointPath.startsWith(codeEntry)) {
                    var firstEntry = splitTraceLink[0].strip();
                    String newTraceLink = TraceLinkUtilities.createTraceLinkString(firstEntry, endpointPath);
                    enrolledTraceLink.add(newTraceLink);
                }
            }
        } else {
            enrolledTraceLink.add(traceLink);
        }
        return enrolledTraceLink;
    }

    protected void compareResults(EvaluationResults<String> results, ExpectedResults expectedResults) {
        Assertions.assertAll(//
                () -> Assertions.assertTrue(results.precision() >= expectedResults.precision(), "Precision " + results
                        .precision() + " is below the expected minimum value " + expectedResults.precision()), //
                () -> Assertions.assertTrue(results.recall() >= expectedResults.recall(), "Recall " + results
                        .recall() + " is below the expected minimum value " + expectedResults.recall()), //
                () -> Assertions.assertTrue(results.f1() >= expectedResults.f1(), "F1 " + results
                        .f1() + " is below the expected minimum value " + expectedResults.f1()), () -> Assertions.assertTrue(results
                                .accuracy() >= expectedResults.accuracy(), "Accuracy " + results
                                        .accuracy() + " is below the expected minimum value " + expectedResults.accuracy()), //
                () -> Assertions.assertTrue(results.phiCoefficient() >= expectedResults.phiCoefficient(), "Phi coefficient " + results
                        .phiCoefficient() + " is below the expected minimum value " + expectedResults.phiCoefficient()));
    }

    /**
     * compares the tlr results with the expected results and creates a new {@link EvaluationResults}.
     *
     * @param arDoCoResult the {@link ArDoCoResult ArDoCoResults}
     * @param goldStandard Collection representing the gold standard
     * @return the result of the comparison
     */
    protected EvaluationResults<String> calculateEvaluationResults(ArDoCoResult arDoCoResult, ImmutableCollection<String> goldStandard) {
        ImmutableList<String> results = createTraceLinkStringList(arDoCoResult);
        Assertions.assertFalse(results.isEmpty());

        Set<String> distinctTraceLinks = new LinkedHashSet<>(results.castToCollection());
        Set<String> distinctGoldStandard = new LinkedHashSet<>(goldStandard.castToCollection());
        int confusionMatrixSum = getConfusionMatrixSum(arDoCoResult);

        var calculator = ClassificationMetricsCalculator.getInstance();
        var classification = calculator.calculateMetrics(distinctTraceLinks, distinctGoldStandard, confusionMatrixSum);
        return new EvaluationResults<>(classification);

    }

    protected abstract ImmutableList<String> createTraceLinkStringList(ArDoCoResult arDoCoResult);

    protected abstract int getConfusionMatrixSum(ArDoCoResult arDoCoResult);

}
