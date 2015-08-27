package water.currents;

import java.util.ArrayList;
import water.util.SB;

/** Apply A Function.  Basic function execution. */
class ASTExec extends AST {
  final AST[] _asts;
  ASTExec( AST[] asts ) { _asts = asts; }
  protected ASTExec( Exec e ) { 
    e.xpeek('(');
    AST ast = e.parse();
    // An eager "must fail at runtime" test.  Not all ASTId's will yield a
    // function, so still need a runtime test.
    if( !(ast instanceof ASTExec) && !(ast instanceof ASTId) && !(ast instanceof ASTFun) )
      e.throwErr("Expected a function but found a "+ast.getClass());
    ArrayList<AST> asts = new ArrayList<>();
    asts.add(0,ast);
    while( e.skipWS() != ')' )
      asts.add(e.parse());
    e.xpeek(')');
    _asts = asts.toArray(new AST[asts.size()]);
  }

  @Override public String str() {
    SB sb = new SB().p('(');
    for( AST ast : _asts )
      sb.p(ast.toString()).p(' ');
    return sb.p(')').toString();
  }

  // Function application.  Execute the first AST and verify that it is a
  // function.  Then call that function's apply method.
  @Override Val exec( Env env ) {
    Val fun = _asts[0].exec(env);
    if( !fun.isFun() )
      throw new IllegalArgumentException("Expected a function but found "+fun.getClass());
    AST ast = ((ValFun)fun)._ast;
    int nargs = ast.nargs();
    if( nargs != -1 && nargs != _asts.length )
      throw new IllegalArgumentException("Incorrect number of arguments; '"+ast+"' expects "+nargs+" but was passed "+_asts.length);
    try (Env.StackHelp stk = env.stk()) {
        return stk.returning(ast.apply(env,stk,_asts));
      }
  }

  // No expected argument count
  @Override int nargs() { return -1; }
}
