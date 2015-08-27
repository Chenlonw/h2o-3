package water.currents;

import hex.quantile.QuantileModel;
import water.*;
import water.fvec.*;
import water.util.*;

// (h2o.impute data col method combine_method gb in.place)

public class ASTImpute extends ASTPrim {
  @Override String str(){ return "h2o.impute";}
  @Override int nargs() { return 1+6; } // (h2o.impute data col method combine_method groupby in.place)
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    // Argument parsing and sanity checking
    // Whole frame being imputed
    Frame fr = stk.track(asts[1].exec(env)).getFrame();

    // Column within frame being imputed
    final int col = (int)asts[2].exec(env).getNum();
    if( col < 0 || col >= fr.numCols() )
      throw new IllegalArgumentException("Column not in range 0 to "+fr.numCols());
    final Vec vec = fr.vec(col);

    // Technique used for imputation
    AST method = null;
    switch( asts[3].exec(env).getStr().toUpperCase() ) {
    case "MEAN"  : method = new ASTMean  (); break;
    case "MEDIAN": method = new ASTMedian(); break;
    case "MODE"  : method = new ASTMode  (); break;
    default: throw new IllegalArgumentException("Method must be one of mean, median or mode");
    }

    // Only for median, how is the median computed on even sample sizes?
    QuantileModel.CombineMethod combine = QuantileModel.CombineMethod.valueOf(asts[4].exec(env).getStr().toUpperCase());

    // Group-by columns.  Empty is allowed, and perfectly normal.
    AST ast = asts[5];
    ASTNumList by2;
    if( ast instanceof ASTNumList  ) by2 = (ASTNumList)ast;
    else if( ast instanceof ASTNum ) by2 = new ASTNumList(((ASTNum)ast)._d.getNum());
    else throw new IllegalArgumentException("Requires a number-list, but found a "+ast.getClass());
    final ASTNumList by = by2;  // Make final, for MRTask closure

    // Inplace updates, or return a new frame?
    final boolean inplace = asts[6].exec(env).getNum() == 1;
    if( inplace && fr._key==null )
      throw new IllegalArgumentException("Can only update in-place named Frames");

    // Compute the imputed value per-group.  Empty groups are allowed and OK.
    final IcedHashMap<ASTGroup.G,IcedDouble> group_impute_map = new IcedHashMap();
    if( by.isEmpty() ) {        // Empty group?  Skip the grouping work
      double res = Double.NaN;
      if( method instanceof ASTMean   ) res = vec.mean();
      if( method instanceof ASTMedian ) res = ASTMedian.median(stk.track(new Frame(vec)),combine);
      if( method instanceof ASTMode   ) res = ASTMode.mode(vec);
      group_impute_map.put(new ASTGroup.G(0,null).fill(0,null,new int[0]),new IcedDouble(res));

    } else {                    // Grouping!
      // Build and run a GroupBy command
      AST ast_grp = new ASTGroup();
      Frame imputes = ast_grp.apply(env,stk,new AST[]{ast_grp,new ASTFrame(fr),by,new ASTNumList(),method,new ASTNumList(col,col+1),new ASTStr("rm")}).getFrame();
     
      // Convert the Frame result to a group/imputation mapping
      final int[] bycols = ArrayUtils.seq(0,imputes.numCols()-1);
      new MRTask() {
        @Override public void map( Chunk cs[] ) {
          Chunk means = cs[cs.length-1]; // Imputed value is last in the frame
          for( int i=0; i<cs[0]._len; i++ ) // For all groups
            group_impute_map.put(new ASTGroup.G(cs.length-1,null).fill(i,cs,bycols),new IcedDouble(means.atd(i)));
        }
      }.doAll(imputes);
      imputes.delete();
    }

    // In not in-place, return a new frame which is the old frame cloned, but
    // for the imputed column which is a copy.
    if( !inplace ) {
      fr = new Frame(fr);
      stk.track(fr).replace(col,vec.makeCopy());
    }

    // Now walk over the data, replace NAs with the imputed results
    final int[] bycols = by.expand4();
    new MRTask() {
      @Override public void map( Chunk cs[] ) {
        Chunk x = cs[col];
        ASTGroup.G g = new ASTGroup.G(bycols.length,null);
        for( int row=0; row<x._len; row++ )
          if( x.isNA(row) )
            x.set(row,group_impute_map.get(g.fill(row,cs,bycols))._val);
      }
    }.doAll(fr);

    return new ValFrame(fr);
  }
}
