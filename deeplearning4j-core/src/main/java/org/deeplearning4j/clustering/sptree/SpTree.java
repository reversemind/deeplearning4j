package org.deeplearning4j.clustering.sptree;

import com.google.common.util.concurrent.AtomicDouble;
import org.apache.commons.math3.util.FastMath;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.max;

/**
 * @author Adam Gibson
 */
public class SpTree implements Serializable {
    private int D;
    public final static int QT_NODE_CAPACITY = 1;

    private INDArray data;
    private int N;
    private boolean isLeaf;
    private INDArray buf;
    private int size;
    private int cumSize;
    private Cell boundary;
    private INDArray centerOfMass;
    private SpTree parent;
    private int[] index = new int[1];
    private int numChildren = 2;

    private List<SpTree> children = new ArrayList<>();

    public SpTree(SpTree parent,int D,INDArray data,INDArray corner,INDArray width) {
        init(parent,D,data,corner,width);
    }


    public SpTree(int d, INDArray data, int n) {
        this.D = d;
        this.data = data;
        N = n;

        INDArray meanY = data.mean(0);
        INDArray minY = data.min(0);
        INDArray maxY = data.max(0);
        //  for(int d = 0; d < D; d++) width[d] = fmax(max_Y[d] - mean_Y[d], mean_Y[d] - min_Y[d]) + 1e-5;
        INDArray width = Nd4j.create(meanY.shape());
        for(int i = 0; i < width.length(); i++) {
            width.putScalar(i, FastMath.max(maxY.getDouble(i) - meanY.getDouble(i),meanY.getDouble(i) - minY.getDouble(i) + Nd4j.EPS_THRESHOLD));
        }

        init(null,D,data,meanY,width);
        fill(N);


    }

    private void init(SpTree parent,int D,INDArray data,INDArray corner,INDArray width) {
        this.parent = parent;
        this.D = D;
        for(int d = 1; d < this.D; d++)
            numChildren *= 2;
        this.data = data;
        isLeaf = true;
        boundary = new Cell(D);
        boundary.setCorner(corner);
        boundary.setWidth(width);
        centerOfMass = Nd4j.create(D);
        buf = Nd4j.create(D);
    }


    private boolean insert(int index) {
        INDArray point = data.slice(index);
        if(!boundary.contains(point))
            return false;

        cumSize++;
        double mult1 = (double) (cumSize - 1) / (double) cumSize;
        double mult2 = 1.0 / (double) cumSize;
        centerOfMass.muli(mult1);
        centerOfMass.addi(point.mul(mult2));
        // If there is space in this quad tree and it is a leaf, add the object here
        if(isLeaf && size < QT_NODE_CAPACITY) {
            this.index[size] = index;
            size++;
            return true;
        }

        boolean anyDuplicate = false;

        for(int i = 0; i < size; i++) {
            INDArray compPoint = data.slice(this.index[i]);
            if(point.getDouble(0) == compPoint.getDouble(0) && point.getDouble(1) == compPoint.getDouble(1))
                return true;
        }

        if(anyDuplicate)
            return true;

        if(isLeaf)
            subDivide();


        // Find out where the point can be inserted
        for(int i = 0; i < numChildren; i++) {
            if(children.get(i).insert(index))
                return true;
        }

        // Otherwise, the point cannot be inserted (this should never happen)
        return false;
    }



    public void subDivide() {
        INDArray newCorner = Nd4j.create(D);
        INDArray newWidth = Nd4j.create(D);
        for( int i = 0; i < numChildren; i++) {
            int div = 1;
            for( int d = 0; d < D; d++) {
                newWidth.putScalar(d,.5 * boundary.width(d));
                if((i / div) % 2 == 1)
                    newCorner.putScalar(d,boundary.corner(d) - .5 * boundary.width(d));
                else
                    newCorner.putScalar(d,boundary.corner(d) + 0.5 * boundary.width(d));
                div *= 2;
            }
            if(children.isEmpty())
                children.add(new SpTree(this, D, data, newCorner, newWidth));
            else
                children.add(i,new SpTree(this,D,data,newCorner,newWidth));

        }

        // Move existing points to correct children
        for(int i = 0; i < size; i++) {
            boolean success = false;
            for(int j = 0; j < this.numChildren; j++) {
                if(!success)
                    success = children.get(j).insert(index[i]);
            }
            index[i] = -1;
        }

        // Empty parent node
        size = 0;
        isLeaf = false;
    }



    /**
     * Compute non edge forces using barnes hut
     * @param pointIndex
     * @param theta
     * @param negativeForce
     * @param sumQ
     */
    public void computeNonEdgeForces(int pointIndex, double theta, INDArray negativeForce, AtomicDouble sumQ) {
        // Make sure that we spend no time on empty nodes or self-interactions
        if(cumSize == 0 || (isLeaf && size == 1 && index[0] == pointIndex))
            return;


        // Compute distance between point and center-of-mass
        buf.assign(data.slice(pointIndex)).subi(centerOfMass);

        double D = Nd4j.getBlasWrapper().dot(buf, buf);
        // Check whether we can use this node as a "summary"
        double max_width = 0.0;
        double cur_width;
        for(int d = 0; d < this.D; d++) {
            cur_width = boundary.width(d);
            max_width = (max_width > cur_width) ? max_width : cur_width;
        }
        // Check whether we can use this node as a "summary"
        if(isLeaf || max_width / FastMath.sqrt(D) < theta) {

            // Compute and add t-SNE force between point and current node
            double Q = 1.0 / (1.0 + D);
            double mult = cumSize * Q;
            sumQ.addAndGet(mult);
            mult *= Q;
            negativeForce.addi(buf.mul(mult));

        }
        else {

            // Recursively apply Barnes-Hut to children
            for(int i = 0; i < numChildren; i++) {
                children.get(i).computeNonEdgeForces(pointIndex, theta, negativeForce, sumQ);
            }

        }
    }


    /**
     *
     * @param rowP a vector
     * @param colP
     * @param valP
     * @param N
     * @param posF
     */
    public void computeEdgeForces(INDArray rowP, INDArray colP, INDArray valP, int N, INDArray posF) {
        if(!rowP.isVector())
            throw new IllegalArgumentException("RowP must be a vector");

        // Loop over all edges in the graph
        double D;
        for(int n = 0; n < N; n++) {
            for(int i = rowP.getInt(n); i < rowP.getInt(n + 1); i++) {

                // Compute pairwise distance and Q-value
                buf.assign(data.slice(n)).subi(data.slice(colP.getInt(i)));

                D = Nd4j.getBlasWrapper().dot(buf,buf);
                D = valP.getDouble(i) / D;

                // Sum positive force
                posF.slice(n).addi(buf.mul(D));

            }
        }
    }


    public boolean isCorrect() {
        for(int n = 0; n < size; n++) {
            INDArray point = data.slice(n);
            if(!boundary.contains(point))
                return false;
        }
        if(!isLeaf) {
            boolean correct = true;
            for(int i = 0; i < numChildren; i++)
                correct = correct && children.get(i).isCorrect();
            return correct;
        }
        else return true;
    }

    /**
     * The depth of the node
     * @return the depth of the node
     */
    public int depth() {
        if(isLeaf)
            return 1;
        int depth = 1;
        int maxChildDepth = 0;
        for(int i = 0; i < children.size(); i++) {
            maxChildDepth = Math.max(maxChildDepth,children.get(0).depth());
        }

        return depth + maxChildDepth;
    }

    private void fill(int n) {
        for(int i = 0; i < n; i++)
            insert(i);
    }
}
