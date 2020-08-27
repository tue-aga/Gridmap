package algorithms;

import algorithms.Moves.SlideMove;
import algorithms.Moves.TakeMove;
import algorithms.Moves.ReleaseMove;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import gui.panels.MosaicPanel;
import model.Cartogram.MosaicCartogram;
import model.Cartogram.MosaicCartogram.MosaicRegion;
import model.Cartogram.MosaicCartogram.Coordinate;
import model.Network;
import model.graph.GraphAlgorithms;
import model.subdivision.Map;
import model.util.LinkedHashMultiset;
import model.util.Multiset;
import model.util.Pair;
import model.util.WeightedObject;

/**
 *
 * @author Rafael Cano <rgcano at gmail.com>
 */
public class MosaicHeuristic {

    public static final double EPS = 1E-6;
    private final Map map;
    public final Network weakDual;
    private final MosaicCartogram originalGrid;
    private final ArrayList<Separator> separators;
    public MosaicCartogram currentGrid = null;
    private MosaicPanel panel = null;
    private ForceDirectedLayout forceDirectedLayout = null;

    public MosaicHeuristic(Map map, Network weakDual, MosaicCartogram originalGrid) {
        this.map = map;
        this.weakDual = weakDual;
        this.originalGrid = originalGrid;
        this.separators = initializeSeparators();
    }

    private ArrayList<Separator> initializeSeparators() {
        Set<Network.Edge> cutEdges = GraphAlgorithms.cutEdges(weakDual);
        ArrayList<Separator> result = new ArrayList<>(cutEdges.size());
        for (Network.Edge cutEdge : cutEdges) {
            result.add(new Separator(weakDual, (Network.Edge) cutEdge));
        }
        return result;
    }

    public MosaicCartogram execute(MosaicPanel panel, final int maxNoImproveIterations, final boolean finalize, final boolean exactTiles) {
        this.panel = panel;
        currentGrid = originalGrid.duplicate();

        initializeGuidingShapesPositions(currentGrid);

        if (!gridIsValid()) {
            System.out.println("Bad bad grid!");
        }

        slideBlocks();
        //Initializing done. Use force directed layout for rest
        forceDirectedLayout = new ForceDirectedLayout(currentGrid, weakDual);

        //We improve it untill we can not improve it within maxNoImproveIterations iterations
        int currentBadIterations = 0;
        //stores the quality of the previous iteration
        Pair<Double, Double> prevQuality = getGridQualityPair();
        long startTime = System.currentTimeMillis();
        //counts the amount of iterations
        int iteration = 0;
        MosaicCartogram bestGrid = currentGrid.duplicate();

        while (currentBadIterations < maxNoImproveIterations) {
            //move the guiding shapes
            forceDirectedLayout.runModel(map);

            //update the regions with releasing and taking.
            runIteration();

            //check if it improved
            Pair<Double, Double> currentQuality = getGridQualityPair();
            if (currentQuality.compareTo(prevQuality) == -1) {
                prevQuality = currentQuality;
                currentBadIterations = 0;
                bestGrid = currentGrid;
            } else {
                currentBadIterations++;
            }
        }
        currentGrid = bestGrid;
        System.out.println("iteration = " + iteration);
        long endTime = System.currentTimeMillis();
        System.out.println("TotalTime = " + (endTime - startTime));
        System.out.println("timePerIteration = " + ((endTime - startTime) / Math.max(1, iteration)));

        System.out.print("iteration limit reached: Finished running model.");

        currentGrid.exportCoordinates("coordinates.coo");
        if (finalize) {
            finalizeCartogram(exactTiles);
        }
        System.out.println("cartogram finalized");

        //If the exact amount of tiles is required, constraints are reduced. Holes and topology violations are then allowed.
        if ((!gridIsValid() && !exactTiles)
            || (exactTiles && !gridIsConnected())) {
            throw new RuntimeException("invalid final grid");
        }
        return currentGrid;
    }

    public MosaicCartogram finalize(MosaicPanel panel, boolean exactTiles) {
        this.panel = panel;
        currentGrid = originalGrid.duplicate();
        if (!gridIsValid()) {
            System.out.println("Bad bad grid!");
        }
        finalizeCartogram(exactTiles);
        return currentGrid;
    }

    private void finalizeCartogram(boolean exactTiles) {
        fillHoles();
        fillAlleys();
        polish(exactTiles);
        printSummary();
        Pair<Double, Double> quality = getGridQualityPair();
        System.out.println("Quality = " + quality.getFirst() + " / " + quality.getSecond());
        System.out.println("Total hex error = " + totalHexError());
    }

    private void initializeGuidingShapesPositions(MosaicCartogram cartogram) {
        for (MosaicRegion region : cartogram.regions()) {
            Coordinate baryRegion = region.barycenter();
            Coordinate baryGuiding = region.getGuidingShape().barycenter();
            region.translateGuidingShape(baryRegion.minus(baryGuiding));
        }
    }

    private void slideBlocks() {
        boolean keepGoing;
        do {
            keepGoing = false;
            for (Separator separator : separators) {
                for (Coordinate c : currentGrid.unitVectors()) {
                    SlideMove sm = new SlideMove(currentGrid, map, separator, c);
                    sm.evaluate();
                    if (sm.isValid()) {
                        sm.execute();
                        keepGoing = true;
                    }
                }
            }
        } while (keepGoing);
    }

    /**
     * Returns true if the cartogram was changed, false otherwise.
     */
    private void runIteration() {

        for (MosaicRegion region : currentGrid.regions()) {
            // Try taking something from the neighbours of a region
            Network.Vertex vertex = region.getVertex();
            //holds whether something changed. If so, then the neighbours need
            //to be update again
            boolean changed = true;
            while (changed) {
                changed = false;
                Set<Coordinate> neighbours = new LinkedHashSet<>(region.neighbours());
                for (Coordinate position : neighbours) {
                    if (region.isDesired(position)) {
                        //try taking it
                        TakeMove tm = new TakeMove(weakDual, currentGrid, position, vertex);
                        if (tm.improves()) {
                            //take it
                            tm.evaluate();
                            if (tm.isValid()) {
                                tm.execute();
                                //neighbours changed, thus need to update the neighbours again
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Try releasing something
        for (MosaicRegion region : currentGrid.regions()) {
            Coordinate[] regionCoordinates = region.occupiedCoordinates();
            for (Coordinate c : regionCoordinates) {
                if (!region.isDesired(c)) {
                    ReleaseMove rm = new ReleaseMove(currentGrid, c);
                    rm.evaluate();
                    if (rm.isValid()) {
                        rm.execute();
                    }
                }
            }
        }
    }

    /**
     * A region associated with a vertex v: (1) must be connected; (2) must be
     * adjacent to all neighbors of v; and (3) must not be connected to any
     * non-neighbor of v.
     */
    private boolean gridIsValid() {
        return currentGrid.isValid();
    }

    private boolean gridIsConnected() {
        return currentGrid.isConnected();
    }

    private Pair<Double, Double> getGridQualityPair() {
        double v1 = currentGrid.quality(false);
        double v2 = currentGrid.quality(true);
        return new Pair<>(v1, v2);
    }

    public int totalHexError() {
        int totalError = 0;
        for (MosaicRegion region : currentGrid.regions()) {
            int hexError = region.getHexError();
            totalError += Math.abs(hexError);
        }
        return totalError;
    }

    public void fillHoles() {
        ArrayList<Set<Coordinate>> holes;
        HashSet<Coordinate> seen = new HashSet<>();
        boolean allSeen;
        do {
            allSeen = true;
            holes = MosaicCartogram.computeHoleBoundaries(currentGrid, currentGrid.getCoordinateSet());
            for (Set<Coordinate> hole : holes) {
                //ArrayList<WeightedObject<BaryCoordinate, Integer>> getCoordinateArray = new ArrayList<>();
                for (Coordinate c : hole) {
                    if (!seen.contains(c)) {
                        allSeen = false;
                        LinkedHashMultiset<Network.Vertex> neighbourMultiplicity = new LinkedHashMultiset<>();
                        for (Coordinate d : c.neighbours()) {
                            Network.Vertex vertex = currentGrid.getVertex(d);
                            if (vertex != null) {
                                neighbourMultiplicity.add(vertex);
                            }
                        }
                        ArrayList<WeightedObject<Network.Vertex, Integer>> candidates = new ArrayList<>();
                        for (Multiset.Entry<Network.Vertex> entry : neighbourMultiplicity.entrySet()) {
                            candidates.add(new WeightedObject<>(entry.getElement(), -entry.getMultiplicity()));
                        }
                        Collections.sort(candidates);
                        boolean fixed = false;
                        for (WeightedObject<Network.Vertex, Integer> obj : candidates) {
                            TakeMove tm = new TakeMove(weakDual, currentGrid, c, obj.getObject());
                            tm.evaluate();
                            if (tm.isValid()) {
                                tm.execute();
                                //updatePanel();
                                fixed = true;
                                break;
                            }
                        }
                        if (!fixed) {
                            seen.add(c);
                        }
                        while (!fixed) {
                            if (candidates.size() > 0) {
                                WeightedObject<Network.Vertex, Integer> obj = candidates.remove(candidates.size() - 1);
                                for (Coordinate d : c.neighbours()) {
                                    Network.Vertex vertex = currentGrid.getVertex(d);
                                    if (vertex == obj.getObject()) {
                                        ReleaseMove rm = new ReleaseMove(currentGrid, d);
                                        rm.evaluate();
                                        if (rm.isValid()) {
                                            rm.execute();
                                            //updatePanel();
                                        }
                                    }
                                }
                            } else {
                                fixed = true;
                            }
                        }
                    }
                }
            }
        } while (!allSeen);
    }

    public void fillAlleys() {
        Set<Coordinate> alleys;
        HashSet<Coordinate> ignoreList = new HashSet<>();
        do {
            alleys = computeAlleys();
            alleys.removeAll(ignoreList);
            for (Coordinate c : alleys) {
                LinkedHashMultiset<Network.Vertex> neighbourMultiplicity = new LinkedHashMultiset<>();
                for (Coordinate d : c.neighbours()) {
                    Network.Vertex vertex = currentGrid.getVertex(d);
                    if (vertex != null) {
                        neighbourMultiplicity.add(vertex);
                    }
                }
                ArrayList<WeightedObject<Network.Vertex, Integer>> candidates = new ArrayList<>();
                for (Multiset.Entry<Network.Vertex> entry : neighbourMultiplicity.entrySet()) {
                    candidates.add(new WeightedObject<>(entry.getElement(), -entry.getMultiplicity()));
                }
                Collections.sort(candidates);
                boolean filled = false;
                for (WeightedObject<Network.Vertex, Integer> obj : candidates) {
                    TakeMove tm = new TakeMove(weakDual, currentGrid, c, obj.getObject());
                    tm.evaluate();
                    if (tm.isValid()) {
                        tm.execute();
                        filled = true;
                        break;
                    }
                }
                if (!filled) {
                    ignoreList.add(c);
                }
            }
        } while (!alleys.isEmpty());
    }

    private Set<Coordinate> computeAlleys() {
        LinkedHashSet<Coordinate> alleys = new LinkedHashSet<>();
        for (MosaicRegion region : currentGrid.regions()) {
            for (Coordinate c : region.neighbours()) {
                if (currentGrid.getVertex(c) == null && isAlley(c)) {
                    alleys.add(c);
                }
            }
        }
        return alleys;
    }

    public boolean isAlley(Coordinate c) {
        Network.Vertex neighbour = null;
        boolean singleRegion = true;
        int count = 0;
        Coordinate[] neighbours = c.neighbours();
        for (Coordinate d : neighbours) {
            Network.Vertex vertex = currentGrid.getVertex(d);
            if (vertex != null) {
                count++;
                if (neighbour == null) {
                    neighbour = vertex;
                } else if (neighbour != vertex) {
                    singleRegion = false;
                }
            }
        }
        if (count == neighbours.length - 1 && !singleRegion) {
            return true;
        }
        return false;
    }

    private void polish(boolean exactTiles) {
        Polisher p = new Polisher(this);
        p.polish(exactTiles);
    }

    private void printSummary() {
        System.out.println("Summary:");
        for (Map.Face face : map.boundedFaces()) {
            MosaicRegion region = currentGrid.getRegion(face.getId());
            int size = region.size();
            int desiredSize = region.getGuidingShape().size();
            region.computeBestOverlay();
            int symDiff = region.getSymmetricDifference();
            System.out.println(String.format("%5s", face.getLabel().getText())
                               + "-> Actual = " + String.format("%3d", size)
                               + ", Desired = " + String.format("%3d", desiredSize)
                               + ", Error = " + String.format("% 6.2f", 100 * (1 - ((double) size) / desiredSize)) + "%"
                               + ", SymDiff = " + String.format("%3d", symDiff));
        }
    }
}
