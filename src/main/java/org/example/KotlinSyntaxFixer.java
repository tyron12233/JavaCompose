package org.example;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import java.util.List;

public class KotlinSyntaxFixer extends TreeTranslator {

    private final TreeMaker treeMaker;
    private final Names names;

    public KotlinSyntaxFixer(Context context) {
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public void visitBlock(JCTree.JCBlock tree) {
        List<JCTree.JCStatement> oldStats = tree.stats;
        ListBuffer<JCTree.JCStatement> newStats = new ListBuffer<>();

        boolean changed = false;

        for (int i = 0; i < oldStats.size(); i++) {
            JCTree.JCStatement current = oldStats.get(i);
            JCTree.JCStatement next = (i + 1 < oldStats.size()) ? oldStats.get(i + 1) : null;

            Name methodName = tryGetErroneousMethodName(current);
            // this means that we have an identifier next to a block
            // Column { }
            // (ERROR) (BLOCK)
            if (methodName != null && next instanceof JCTree.JCBlock) {
                JCTree.JCBlock lambdaBody = (JCTree.JCBlock) next;
                treeMaker.at(lambdaBody.pos);

                JCTree.JCMethodInvocation mergedCall = createKotlinStyleCall(methodName, lambdaBody);

                newStats.append(treeMaker.Exec(mergedCall));

                // we dont want to consume the BLOCK tree again
                i++;
                changed = true;
            } else {
                newStats.append(current);
            }
        }

        if (changed) {
            tree.stats = newStats.toList();
        }

        super.visitBlock(tree);
    }

    private Name tryGetErroneousMethodName(JCTree.JCStatement stat) {
        // parser usually wraps the error in an expression statement
        if (stat.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
            JCTree.JCExpression expr = ((JCTree.JCExpressionStatement) stat).getExpression();

            if (expr.getKind() == Tree.Kind.ERRONEOUS) {
                JCTree.JCErroneous erroneous = (JCTree.JCErroneous) expr;

                List<? extends JCTree> errorTrees = erroneous.getErrorTrees();

                // we expect only one thing in the error, an identifier or in the future method calls too
                // e.g. Column {} or Column() {}
                if (errorTrees != null && errorTrees.size() == 1) {
                    JCTree content = errorTrees.getFirst();
                    if (content.getKind() == Tree.Kind.IDENTIFIER) {
                        return ((JCTree.JCIdent) content).getName();
                    }
                }
            }
        }

        return null;
    }

    private JCTree.JCMethodInvocation createKotlinStyleCall(Name methodName, JCTree.JCBlock body) {
        // content lambda: () -> { body }
        JCTree.JCLambda lambda = treeMaker.Lambda(
                com.sun.tools.javac.util.List.nil(),
                body
        );


        return treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                treeMaker.Ident(methodName),
                com.sun.tools.javac.util.List.of(lambda)
        );
    }
}
