package edu.cmu.cs.graphics.hopper.explore;

import edu.cmu.cs.graphics.hopper.control.Control;
import edu.cmu.cs.graphics.hopper.control.ControlProvider;
import edu.cmu.cs.graphics.hopper.control.ControlProviderDefinition;
import edu.cmu.cs.graphics.hopper.io.IOUtils;
import edu.cmu.cs.graphics.hopper.problems.ProblemDefinition;
import net.sf.javaml.core.kdtree.KDTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** A baseline explorer that doesn't do anything smart in terms of prioritizing
 * which examples to send to the user or which controls to test at each timestep. */
public class SimpleExplorer<C extends Control> extends Explorer<C> {
    private static final Logger log = LoggerFactory.getLogger(SimpleExplorer.class);

    //Control providers/sequences usable by this explorer
    //Just a way of keeping track of which controls exist already in the ensemble
    LinkedHashSet<ControlProviderDefinition<C>> controlEnsemble;
    Iterator<ControlProviderDefinition<C>> nextControlProviderIter;

    public SimpleExplorer() {
        controlEnsemble = new LinkedHashSet<ControlProviderDefinition<C>>();
    }

    @Override
    public void loadEnsemble(String inputEnsemblePath) {
        //For now: load sol files, add corresponding controls to ensemble
        List<ProblemSolutionEntry> entries = IOUtils.instance().loadAllProblemSolutionEntriesInDir(inputEnsemblePath);
        for (ProblemSolutionEntry entry : entries)
            addToControlEnsemble(entry.problem, entry.solution);
    }

    @Override
    protected void initExploration() {
        //
    }

    @Override
    protected void prepareForProblem(ProblemDefinition problemDef) {
        nextControlProviderIter = controlEnsemble.iterator();
    }

    @Override
    protected ProblemDefinition getNextProblemToTest() {
        if (unsolvedProblems.size() > 0)
            return unsolvedProblems.iterator().next();
        return null;
    }

    @Override
    protected ControlProviderDefinition<C> getNextControlSequence(ProblemDefinition p) {
        //Just return next sequence in the list, if available
        ControlProviderDefinition<C> provider = null;
        if (nextControlProviderIter.hasNext()) {
            provider = nextControlProviderIter.next();
        }
        return provider;
    }

    @Override
    protected ProblemDefinition getNextChallengeProblem() {
        //Return something arbitrary from the map of marked oracle problems
        if (!oracleChallengeProblems.isEmpty())
            return oracleChallengeProblems.iterator().next();
        return null;
    }

    @Override
    protected void addToControlEnsemble(ProblemDefinition problem, ControlProviderDefinition<C> control) {
        //Add the new solution to our ensemble (control vocabulary)
        //Note that we don't particular care about the problem it solved in this simple explorer

        if (!controlEnsemble.contains(control))
            controlEnsemble.add(control);
        else {
            log.info("Note: Declined to add a duplicate challenge solution to control ensemble.");
        }
    }
}
