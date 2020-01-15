package org.vanted.addons.gsmmexplorer.decomposition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.AttributeHelper;
import org.FolderPanel;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.vanted.addons.gsmmexplorer.core.GsmmExplorerConstants;
import org.vanted.addons.gsmmexplorer.core.GsmmExplorerController;
import org.vanted.addons.gsmmexplorer.core.GsmmExplorerSession;
import org.vanted.addons.gsmmexplorer.core.GsmmExplorerTools;
import org.vanted.addons.gsmmexplorer.graphs.BaseGraph;
import org.vanted.addons.gsmmexplorer.graphs.SubsystemGraph;
import org.vanted.addons.gsmmexplorer.ui.GsmmExplorerTab;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import de.ipk_gatersleben.ag_nw.graffiti.plugins.ios.sbml.SBML_Constants;

/**
 * 
 * @author Michael Aichem
 *
 */
public abstract class GsmmDecompositionAlgorithm {

	// Returns arraylist of subsystems, the subsystems itself should only contain
	// references to the basegraph.
	protected abstract ArrayList<SubsystemGraph> runSpecific();

	// returns decomposition?
	public GsmmDecomposition run(boolean addTransporterSubsystem, boolean addDefaultSubsystem,
			boolean splitDefaultSubsystem, int minimumSubsystemSize) {

		GsmmExplorerSession currentSession = GsmmExplorerController.getInstance().getCurrentSession();
		GsmmExplorerTab tab = GsmmExplorerController.getInstance().getTab();

		if (this.requiresCloning()) {
			GsmmExplorerTools.getInstance().cloneSpecies(tab.getClonableSpecies(), tab.getClonableSpeciesThreshold());
		}
		ArrayList<SubsystemGraph> specificSubsystems = runSpecific();

		GsmmDecomposition decomposition = new GsmmDecomposition(specificSubsystems);

		if (addTransporterSubsystem) {
			decomposition.addSubsystem(this.determineTransporterSubsystem(decomposition));
		}

		if (addDefaultSubsystem) {
			SubsystemGraph defaultSubsystem = this.determineDefaultSubsystem(decomposition);

			if (splitDefaultSubsystem) {
				ArrayList<SubsystemGraph> subsystems = this.splitDefaultSubsystem(decomposition, defaultSubsystem,
						minimumSubsystemSize);
				for (SubsystemGraph subsystem : subsystems) {
					decomposition.addSubsystem(subsystem);
				}
				SubsystemGraph remainingDefaultSubsystem = determineDefaultSubsystem(decomposition);
				remainingDefaultSubsystem.setName("Default Subsystem 0");
				decomposition.addSubsystem(remainingDefaultSubsystem);
			} else {
				decomposition.addSubsystem(defaultSubsystem);
			}
		}

		return decomposition;
		// todo combine them to create a Decomposition.
		// possibly create the additional subsystem
	}

	private SubsystemGraph determineTransporterSubsystem(GsmmDecomposition decomposition) {
		HashSet<Node> speciesNodes = new HashSet<>();
		HashSet<Node> reactionNodes = new HashSet<>();
		HashSet<Edge> edges = new HashSet<>();

		for (Node reactionNode : GsmmExplorerController.getInstance().getCurrentSession().getBaseGraph()
				.getReactionNodes()) {
			if (!decomposition.hasReactionBeenClassified(reactionNode)) {
				Collection<Edge> inEdges = reactionNode.getDirectedInEdges();
				Collection<Edge> outEdges = reactionNode.getDirectedOutEdges();
				if ((inEdges.size() == 1) && (outEdges.size() == 1)) {
					Node inNeighbor = null;
					Node outNeighbor = null;
					Edge inEdge = null;
					Edge outEdge = null;
					for (Edge edge : inEdges) {
						inEdge = edge;
						inNeighbor = edge.getSource();
					}
					for (Edge edge : outEdges) {
						outEdge = edge;
						outNeighbor = edge.getTarget();
					}

					if (AttributeHelper.hasAttribute(inNeighbor, SBML_Constants.SBML, SBML_Constants.COMPARTMENT)
							&& AttributeHelper.hasAttribute(outNeighbor, SBML_Constants.SBML,
									SBML_Constants.COMPARTMENT)) {
						String comp0 = (String) AttributeHelper.getAttributeValue(inNeighbor, SBML_Constants.SBML,
								SBML_Constants.COMPARTMENT, "", "");
						String comp1 = (String) AttributeHelper.getAttributeValue(outNeighbor, SBML_Constants.SBML,
								SBML_Constants.COMPARTMENT, "", "");
						if (comp0 != comp1) {
							speciesNodes.add(inNeighbor);
							speciesNodes.add(outNeighbor);
							reactionNodes.add(reactionNode);
							edges.add(inEdge);
							edges.add(outEdge);
						}
					}
				}
			}
		}

		return new SubsystemGraph(GsmmExplorerConstants.TRANSPORTER_SUBSYSTEM, speciesNodes, reactionNodes, edges);
	}

	private SubsystemGraph determineDefaultSubsystem(GsmmDecomposition decomposition) {
		HashSet<Node> speciesNodes = new HashSet<>();
		HashSet<Node> reactionNodes = new HashSet<>();
		HashSet<Edge> edges = new HashSet<>();

		for (Node reactionNode : GsmmExplorerController.getInstance().getCurrentSession().getBaseGraph()
				.getReactionNodes()) {
			if (!decomposition.hasReactionBeenClassified(reactionNode)) {
				for (Edge inEdge : reactionNode.getDirectedInEdges()) {
					edges.add(inEdge);
					speciesNodes.add(inEdge.getSource());
				}
				for (Edge outEdge : reactionNode.getDirectedOutEdges()) {
					edges.add(outEdge);
					speciesNodes.add(outEdge.getTarget());
				}
				reactionNodes.add(reactionNode);
			}
		}

		return new SubsystemGraph(GsmmExplorerConstants.DEFAULT_SUBSYSTEM, speciesNodes, reactionNodes, edges);
	}

	private ArrayList<SubsystemGraph> splitDefaultSubsystem(GsmmDecomposition decomposition,
			SubsystemGraph defaultSubsystem, int threshold) {

		ArrayList<SubsystemGraph> subsystems = new ArrayList<>();

		Graph workingCopy = new AdjListGraph();
		HashMap<Node, Node> original2CopiedNodes = new HashMap<>();
		HashMap<Node, Node> copied2OriginalNodes = new HashMap<>();
		// HashMap<Edge, Edge> original2CopiedEdges = new HashMap<>();
		HashMap<Edge, Edge> copied2OriginalEdges = new HashMap<>();

		for (Node speciesNode : defaultSubsystem.getSpeciesNodes()) {
			Node newNode = workingCopy.addNodeCopy(speciesNode);
			original2CopiedNodes.put(speciesNode, newNode);
			copied2OriginalNodes.put(newNode, speciesNode);
		}
		for (Node reactionNode : defaultSubsystem.getReactionNodes()) {
			Node newNode = workingCopy.addNodeCopy(reactionNode);
			original2CopiedNodes.put(reactionNode, newNode);
			copied2OriginalNodes.put(newNode, reactionNode);
		}

		for (Edge edge : defaultSubsystem.getEdges()) {
			Node sourceNode = original2CopiedNodes.get(edge.getSource());
			Node tragetNode = original2CopiedNodes.get(edge.getTarget());
			Edge newEdge = workingCopy.addEdgeCopy(edge, sourceNode, tragetNode);
			copied2OriginalEdges.put(newEdge, edge);
		}

		Set<Set<Node>> connComps = GraphHelper.getConnectedComponents(workingCopy.getNodes());

		int i = 1;
		for (Set<Node> connComp : connComps) {
			if (connComp.size() >= threshold) {
				HashSet<Node> speciesNodes = new HashSet<>();
				HashSet<Node> reactionNodes = new HashSet<>();
				HashSet<Edge> edges = new HashSet<>();
				for (Node node : connComp) {
					Node originalNode = copied2OriginalNodes.get(node);
					if (GsmmExplorerTools.getInstance().isSpecies(originalNode)) {
						speciesNodes.add(originalNode);
					} else if (GsmmExplorerTools.getInstance().isReaction(originalNode)) {
						reactionNodes.add(originalNode);
					}
					for (Edge edge : node.getEdges()) {
						Edge originalEdge = copied2OriginalEdges.get(edge);
						if (defaultSubsystem.getEdges().contains(originalEdge)) {
							edges.add(originalEdge);
						}
					}
				}
				subsystems.add(new SubsystemGraph(GsmmExplorerConstants.DEFAULT_SUBSYSTEM + " " + i, speciesNodes,
						reactionNodes, edges));
				i += 1;
			}
		}

		return subsystems;
	}

	protected ArrayList<SubsystemGraph> determineSubsystemsFromReactionAttributes(String attributeName,
			String separator) {

		BaseGraph baseGraph = GsmmExplorerController.getInstance().getCurrentSession().getBaseGraph();
		GsmmExplorerSession currentSession = GsmmExplorerController.getInstance().getCurrentSession();

		HashSet<String> allSubsystemNames = new HashSet<>();

		for (Node reactionNode : baseGraph.getReactionNodes()) {
			String subsystemName = currentSession.getNodeAttribute(reactionNode, attributeName);
			if (subsystemName != "") {
				String[] subsystemNames = subsystemName.split(separator);
				allSubsystemNames.addAll(Arrays.asList(subsystemNames));
			}
		}

		HashMap<String, SubsystemGraph> subsystemMap = new HashMap<>();

		for (String subsystemName : allSubsystemNames) {
			subsystemMap.put(subsystemName,
					new SubsystemGraph(subsystemName, new HashSet<>(), new HashSet<>(), new HashSet<>()));
		}

		for (Node reactionNode : baseGraph.getReactionNodes()) {
			String subsystemName = currentSession.getNodeAttribute(reactionNode, attributeName);
			if (subsystemName != "") {
				String[] subsystemNames = subsystemName.split(separator);
				for (String currentSubsystemName : subsystemNames) {
					subsystemMap.get(currentSubsystemName).addReaction(reactionNode);
					for (Edge incidentEdge : reactionNode.getEdges()) {
						// if (incidentEdge.isDirected()) {
						subsystemMap.get(currentSubsystemName).addEdge(incidentEdge);
						if (incidentEdge.getSource() == reactionNode) {
							subsystemMap.get(currentSubsystemName).addSpecies(incidentEdge.getTarget());
						} else {
							subsystemMap.get(currentSubsystemName).addSpecies(incidentEdge.getSource());
						}
						// }
					}

					// for (Node speciesNode : reactionNode.getNeighbors()) {
					// subsystemMap.get(currentSubsystemName).addSpecies(speciesNode);
					// }

				}
			}
		}
		ArrayList<SubsystemGraph> res = new ArrayList<>();
		for (SubsystemGraph subsystem : subsystemMap.values()) {
			res.add(subsystem);
		}
		return res;
	}

	public abstract boolean requiresCloning();

	public abstract FolderPanel getFolderPanel();

	public abstract String getName();
}