package org.example;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

/**
 * PASS 5: The "Magic".
 * Wraps the method body in restart groups, adds skipping logic,
 * and generates the recursive update scope lambda.
 */
public class ComposableBodyTransformer extends TreeTranslator {

    private final TreeMaker treeMaker;
    private final Names names;

    // Standard variable names used in Compose
    private static final String COMPOSER_VAR = "composer";
    private static final String CHANGED_VAR = "changed";
    private static final String SCOPE_VAR = "scope";

    public ComposableBodyTransformer(Context context) {
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl tree) {
        // Only transform if it has a body and looks Composable
        // (In pass 4, we already injected 'composer' and 'changed' params)
        if (tree.body != null && isComposable(tree)) {

            Name composerName = names.fromString(COMPOSER_VAR);
            Name changedName = names.fromString(CHANGED_VAR);

            // 1. Generate Deterministic Key (Hash of method name for now)
            int key = tree.name.toString().hashCode();

            // 2. Generate: composer = composer.startRestartGroup(key)
            JCTree.JCStatement startGroup = treeMaker.Exec(
                    treeMaker.Assign(
                            treeMaker.Ident(composerName),
                            createMethodCall(treeMaker.Ident(composerName), "startRestartGroup", treeMaker.Literal(key))
                    )
            );

            // 3. Generate: composer.endRestartGroup() returning Scope
            // Scope scope = composer.endRestartGroup();
            JCTree.JCVariableDecl scopeDecl = treeMaker.VarDef(
                    treeMaker.Modifiers(0), // local var
                    names.fromString(SCOPE_VAR),
                    treeMaker.Ident(names.fromString("Scope")), // Assumes Scope class is imported
                    createMethodCall(treeMaker.Ident(composerName), "endRestartGroup")
            );

            // 4. Generate Skipping Condition
            // if ((changed & 1) == 0 && composer.getSkipping())
            JCTree.JCExpression skippingCondition = createSkippingCondition(changedName, composerName);

            // 5. Generate True Block (Skip)
            // composer.skipToGroupEnd();
            JCTree.JCStatement skipCall = treeMaker.Exec(
                    createMethodCall(treeMaker.Ident(composerName), "skipToGroupEnd")
            );

            // 6. Generate False Block (Original Body)
            JCTree.JCBlock originalBody = tree.body;

            // 7. Combine into If-Else
            JCTree.JCIf ifSkipping = treeMaker.If(
                    skippingCondition,
                    treeMaker.Block(0, List.of(skipCall)),
                    originalBody
            );

            // 8. Generate Restart Logic (Recursive Lambda)
            // if (scope != null) { scope.updateScope(...) }
            JCTree.JCStatement restartLogic = createRestartLogic(tree, scopeDecl.name);

            // 9. Reassemble Method Body
            ListBuffer<JCTree.JCStatement> newStats = new ListBuffer<>();
            newStats.append(startGroup);
            newStats.append(ifSkipping);
            newStats.append(scopeDecl); // Must declare scope variable
            newStats.append(restartLogic);

            tree.body = treeMaker.Block(0, newStats.toList());
        }

        super.visitMethodDef(tree);
    }

    /**
     * Logic: (changed & 1) == 0 && composer.getSkipping()
     * 1 is the bitmask for "Force Recompose". If it is 0, we are allowed to check skipping.
     */
    private JCTree.JCExpression createSkippingCondition(Name changedName, Name composerName) {
        // (changed & 1)
        JCTree.JCExpression bitwise = treeMaker.Binary(
                JCTree.Tag.BITAND,
                treeMaker.Ident(changedName),
                treeMaker.Literal(1)
        );

        // (changed & 1) == 0
        JCTree.JCExpression notForced = treeMaker.Binary(
                JCTree.Tag.EQ,
                bitwise,
                treeMaker.Literal(0)
        );

        // composer.getSkipping()
        JCTree.JCExpression getSkipping = createMethodCall(
                treeMaker.Ident(composerName),
                "getSkipping"
        );

        // AND them together
        return treeMaker.Binary(JCTree.Tag.AND, notForced, getSkipping);
    }

    /**
     * Creates:
     * if (scope != null) {
     *     scope.updateScope( (composer, force) -> MyMethod(args..., composer, changed | 1) );
     * }
     */
    private JCTree.JCStatement createRestartLogic(JCTree.JCMethodDecl methodTree, Name scopeVarName) {
        // 1. Create Lambda Params: (Composer c, int force)
        Name lambdaC = names.fromString("c");
        Name lambdaI = names.fromString("i"); // usually 'force' or just unused integer

        JCTree.JCVariableDecl paramC = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER), lambdaC, treeMaker.Ident(names.fromString("Composer")), null
        );
        JCTree.JCVariableDecl paramI = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER), lambdaI, treeMaker.TypeIdent(TypeTag.INT), null
        );

        ListBuffer<JCTree.JCExpression> recursiveArgs = new ListBuffer<>();

        // add original parameters (e.g. String text)
        // we skip the last two because they are 'composer' and 'changed' which we injected earlier
        int userParamsCount = methodTree.params.size() - 2;

        for (int i = 0; i < userParamsCount; i++) {
            recursiveArgs.append(treeMaker.Ident(methodTree.params.get(i).name));
        }

        recursiveArgs.append(treeMaker.Ident(lambdaC));

        // add 'changed | 1'
        // logic: arg 'changed' | 1
        JCTree.JCExpression changedOrOne = treeMaker.Binary(
                JCTree.Tag.BITOR,
                treeMaker.Ident(names.fromString(CHANGED_VAR)),
                treeMaker.Literal(1)
        );
        recursiveArgs.append(changedOrOne);

        // create the recursive call: MyMethod(...)
        JCTree.JCMethodInvocation recursiveCall = treeMaker.Apply(
                List.nil(),
                treeMaker.Ident(methodTree.name),
                recursiveArgs.toList()
        );

        // create lambda body
        JCTree.JCLambda lambda = treeMaker.Lambda(
                List.of(paramC, paramI),
                treeMaker.Block(0, List.of(treeMaker.Exec(recursiveCall)))
        );

        // create scope.updateScope(lambda)
        JCTree.JCStatement updateCall = treeMaker.Exec(
                createMethodCall(treeMaker.Ident(scopeVarName), "updateScope", lambda)
        );

        // wrap in "if (scope != null)"
        JCTree.JCExpression scopeNotNull = treeMaker.Binary(
                JCTree.Tag.NE,
                treeMaker.Ident(scopeVarName),
                treeMaker.Literal(TypeTag.BOT, null)
        );

        return treeMaker.If(scopeNotNull, updateCall, null);
    }

    private JCTree.JCMethodInvocation createMethodCall(JCTree.JCExpression receiver, String methodName, JCTree.JCExpression... args) {
        return treeMaker.Apply(
                List.nil(),
                treeMaker.Select(receiver, names.fromString(methodName)),
                List.from(args)
        );
    }

    private boolean isComposable(JCTree.JCMethodDecl tree) {
        if (tree.params.size() < 2) return false;

        JCTree.JCVariableDecl lastParam = tree.params.last();
        if (!lastParam.name.toString().equals(CHANGED_VAR)) return false;

        String name = tree.name.toString();
        return !name.equals("<init>") && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }
}