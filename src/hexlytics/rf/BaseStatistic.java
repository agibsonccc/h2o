/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hexlytics.rf;

import hexlytics.rf.Data.Row;
import java.util.Arrays;

public abstract class BaseStatistic {

  // PETA TODO This should not be here!!!!
  public static final double MIN_ERROR_RATE = 0.0;
  
  /** Returns the best split for a given column   */
  protected abstract Split columnSplit(int colIndex);
  
  /** Split descriptor for a particular column. 
   * 
   * Holds the column name and the split point, which is the last column class
   * that will go to the left tree. If the column index is -1 then the split
   * value indicates the return value of the node.
   */
  public static class Split {
    public final int column;
    public final int split;
    public final double fitness;
    public Split(int column, int split, double fitness) {
      this.column = column;
      this.split = split;
      this.fitness = fitness;
    }
    public static Split constant(int result) {  return new Split(-1, result, -1); }
    public static Split impossible(int result) { return new Split(-2, result, -1);  }  
    public final boolean isLeafNode() { return column < 0; }    
    public final boolean isConstant() { return column == -1; }    
    public final boolean isImpossible() { return column == -2;  } 
    public final boolean betterThan(Split other) { return fitness > other.fitness; }
  }

  protected final double[][][] columnDists_;  /// Column distributions for the given statistic
  protected final int[] columns_;// Columns that are currently used.
  
  /** Aggregates the given column's distribution to the provided array and 
   * returns the sum of weights of that array.  */
  protected final double aggregateColumn(int colIndex, double[] dist) {
    double sum = 0;
    for (int j = 0; j < columnDists_[colIndex].length; ++j) {
      for (int i = 0; i < dist.length; ++i) {
        sum += columnDists_[colIndex][j][i];
        dist[i] += columnDists_[colIndex][j][i]; 
      }
    }
    return sum;
  }

  protected void showColumnDist(int colIndex) {
    for (double[] d : columnDists_[colIndex])
        System.out.print(" "+Utils.sum(d));
  }
  
  protected final int singleClass(double[] dist) {
    int result = -1;
    for (int i = 0; i < dist.length; ++i)
      if (dist[i] != 0)
        if (result == dist[i]) {
          // pass
        } else if (result == -1) {
          result = i;
        } else {
          result = -1;
          break;
        }
    return result;
  }
  
  private final int[] tempCols_;
  private final int _features;
  
  public BaseStatistic(Data data, int features) {
    _features = features;
    // first create the column distributions
    columnDists_ = new double[data.columns()][][];
    for (int i = 0; i < columnDists_.length; ++i)
      columnDists_[i] = new double[data.columnClasses(i)][data.classes()];
    // create the columns themselves
    columns_ = new int[_features];
    // create the temporary column array to choose cols from
    tempCols_ = new int[data.columns()];
  }
  
  /** Resets the statistic so that it can be used to compute new node. 
   */
  public void reset(Data data) {
    // first get the columns for current split
    Arrays.fill(tempCols_,0);
    int i = 0;
    while (i < columns_.length) {
      int off = data.random().nextInt(tempCols_.length);
      if (tempCols_[off] == -1)
        continue;
      tempCols_[off] = -1;
      columns_[i] = off;
      ++i;
    }
    // reset the column distributions for those
    for (int j : columns_) 
      for (double[] d: columnDists_[j])
        Arrays.fill(d,0.0);
    // and now the statistic is ready
  }
  
  /** Adds the given row to the statistic.    */
  public void add(Row row) {
    for (int i : columns_)
      columnDists_[i][row.getColumnClass(i)][row.classOf()] += row.weight();
  }
  
  /** Calculates the best split and returns it.  */
  public Split split() {
    Split bestSplit = columnSplit(columns_[0]);
    if (!bestSplit.isConstant())
      for (int j = 1; j < columns_.length; ++j) {
        Split s = columnSplit(columns_[j]);
        if (s.betterThan(bestSplit))
        bestSplit = s;
      }
    return bestSplit;
  }
}
