package org.vanted.addons.mme.analysis;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.commons.math.MathException;
import org.apache.commons.math.distribution.HypergeometricDistributionImpl;
import org.graffiti.graph.Node;
import org.vanted.addons.mme.core.MMEController;
import org.vanted.addons.mme.graphs.BaseGraph;
import org.vanted.addons.mme.graphs.SubsystemGraph;

import de.ipk_gatersleben.ag_nw.graffiti.plugins.ios.sbml.SBMLSpeciesHelper;

/**
 * This class implements an over-representation analysis (ORA). A list of
 * differentially expressed metabolites is checked against a reference set of
 * metabolites. The latter may be the set of all metabolites present in the
 * model, if not specified otherwise by the user. To correct for ultiple
 * testing, the false discovery rate (FDR) is controlled according to the method
 * by Benjamini and Hochberg.
 * 
 * @author Michael Aichem
 *
 */
public class OverRepresentationAnalysis {

//	private HashSet<String> differentiallyExpressedMetaboliteStrings;
//	private HashSet<String> referenceMetaboliteStrings;

	private HashSet<Node> differentiallyExpressedMetaboliteNodes;
	private HashSet<Node> referenceMetaboliteNodes;

	private double significanceLevel = 0.05;

	private HashMap<SubsystemGraph, Double> pValueMap;
	private HashSet<SubsystemGraph> significantSubsystems;

	/**
	 * Constructs an instance of an over-representation analysis. During
	 * construction, the lists
	 * {@link OverRepresentationAnalysis.differentiallyExpressedMetaboliteNodes} and
	 * {@link OverRepresentationAnalysis.referenceMetaboliteNodes} are initialised
	 * based on the String lists.
	 * 
	 * @param differentiallyExpressedMetaboliteStrings
	 * @param referenceMetaboliteStrings
	 * @throws IOException
	 */
	public OverRepresentationAnalysis(String pathToDifferentiallyExpressedFile, String pathToReferenceFile)
			throws IOException {

		BaseGraph baseGraph = MMEController.getInstance().getCurrentSession().getBaseGraph();
		SBMLSpeciesHelper speciesHelper = new SBMLSpeciesHelper(baseGraph.getOriginalGraph());

		differentiallyExpressedMetaboliteNodes = new HashSet<Node>();
		referenceMetaboliteNodes = new HashSet<Node>();

		FileReader differentiallyFileReader = new FileReader(pathToDifferentiallyExpressedFile);
		BufferedReader differentiallyBufferedReader = new BufferedReader(differentiallyFileReader);

		HashSet<String> differentiallyExpressedMetaboliteStrings = new HashSet<String>();

		String line = differentiallyBufferedReader.readLine();
		while (line != null) {
			differentiallyExpressedMetaboliteStrings.add(line);
			line = differentiallyBufferedReader.readLine();
		}

		HashSet<String> referenceMetaboliteStrings = new HashSet<String>();
		if (pathToReferenceFile != null) {
			FileReader referenceFileReader = new FileReader(pathToReferenceFile);
			BufferedReader referenceBufferedReader = new BufferedReader(referenceFileReader);
			line = referenceBufferedReader.readLine();
			while (line != null) {
				referenceMetaboliteStrings.add(line);
				line = referenceBufferedReader.readLine();
			}
		}

		for (Node speciesNode : baseGraph.getOriginalSpeciesNodes()) {
			if (differentiallyExpressedMetaboliteStrings.contains(speciesHelper.getID(speciesNode))) {
				differentiallyExpressedMetaboliteNodes.add(speciesNode);
				referenceMetaboliteNodes.add(speciesNode);
			}
			if (referenceMetaboliteStrings.isEmpty()
					|| referenceMetaboliteStrings.contains(speciesHelper.getID(speciesNode))) {
				referenceMetaboliteNodes.add(speciesNode);
			}
		}
		// set the nodelists
		// check for empty referenceList
		// go over originalGraph! No copies necessary!
	}

	/**
	 * Calculates the p-values for the subsystems of the current decomposition
	 * assuming a hypergeometric distribution (Fishers Exact Test, one-tailed).
	 * During this, the list {@link OverRepresentationAnalysis.pValueMap} is
	 * initialised.
	 */
	private void calculatePValues() {
//		MathUtils.binomialCoefficient(5, 3);

		pValueMap = new HashMap<SubsystemGraph, Double>();

		ArrayList<SubsystemGraph> subsystems = MMEController.getInstance().getCurrentSession().getOverviewGraph()
				.getDecomposition().getSubsystems();
		BaseGraph baseGraph = MMEController.getInstance().getCurrentSession().getBaseGraph();

		int referenceNumber = referenceMetaboliteNodes.size();
		int differentiallyExpressedNumber = differentiallyExpressedMetaboliteNodes.size();

		HypergeometricDistributionImpl hgd = new HypergeometricDistributionImpl(referenceNumber,
				differentiallyExpressedNumber, 0);

		int differentiallyExpressedInSubsystem;
		int referenceInSubsystem;
		HashSet<Node> differentialTemp = new HashSet<Node>();
		HashSet<Node> referenceTemp = new HashSet<Node>();

		for (SubsystemGraph subsystem : subsystems) {
			differentialTemp.clear();
			referenceTemp.clear();
			for (Node subsystemMetabolite : subsystem.getSpeciesNodes()) {
				// Subsystem kann geklonte MEtabolite enthalten?!
				// Nochmal ne temporaere hashmap anlegen?
				Node originalMetaboliteNode = baseGraph.getOriginalNode(subsystemMetabolite);

				if (differentiallyExpressedMetaboliteNodes.contains(originalMetaboliteNode)) {
					differentialTemp.add(originalMetaboliteNode);
					referenceTemp.add(originalMetaboliteNode);
				} else if (referenceMetaboliteNodes.contains(originalMetaboliteNode)) {
					referenceTemp.add(originalMetaboliteNode);
				}

			}
			differentiallyExpressedInSubsystem = differentialTemp.size();
			referenceInSubsystem = referenceTemp.size();
			hgd.setSampleSize(referenceInSubsystem);

			double cumulativeProbability = 0.0;
			try {
				cumulativeProbability = hgd.cumulativeProbability(differentiallyExpressedInSubsystem);
				// Switch to right-tailed test if probability > .5
				if (cumulativeProbability > 0.5) {
					cumulativeProbability = 1.0 - hgd.cumulativeProbability(differentiallyExpressedInSubsystem - 1);
				}
			} catch (MathException e) {
				e.printStackTrace();
			}

			pValueMap.put(subsystem, Double.valueOf(cumulativeProbability));
		}

	}

	/**
	 * Calculates the critical values for the FDR correction procedure introduced by
	 * Benjamini and Hochberg and decides which subsystems are significantly
	 * differentially expressed. During this, the list
	 * {@link OverRepresentationAnalysis.significantSubsystems} is initialised.
	 * 
	 * Citation: Benjamini, Y., & Hochberg, Y. (1995). Controlling the false
	 * discovery rate: a practical and powerful approach to multiple testing.
	 * Journal of the Royal statistical society: series B (Methodological), 57(1),
	 * 289-300.
	 */
	private void doFDRCorrection() {

		this.significantSubsystems = new HashSet<SubsystemGraph>();

		double counter = 1.0;
		double numberOfSubsystems = (double) pValueMap.keySet().size();
		boolean conditionStillSatisfied = true;

		while (conditionStillSatisfied) {
			SubsystemGraph currentMinSubsystem = pValueMap.keySet().iterator().next();
			double currentMinP = pValueMap.get(currentMinSubsystem);

			for (SubsystemGraph subsystem : pValueMap.keySet()) {
				if (pValueMap.get(subsystem) < currentMinP) {
					currentMinSubsystem = subsystem;
					currentMinP = pValueMap.get(subsystem);
				}
			}

			if (currentMinP <= ((counter / numberOfSubsystems) * significanceLevel)) {
				significantSubsystems.add(currentMinSubsystem);
				counter += 1.0;
				pValueMap.remove(currentMinSubsystem);
			} else {
				conditionStillSatisfied = false;
			}

		}
	}

	/**
	 * Via this method, the entire ORA is performed and the significantly
	 * differentially expressed subsystems are returned
	 * 
	 * @return a HashSet that contains the subsystems that have been found
	 *         significantly differentially expressed
	 */
	public HashSet<SubsystemGraph> getSignificantSubsystems() {
		calculatePValues();
		doFDRCorrection();
		return this.significantSubsystems;
	}

}