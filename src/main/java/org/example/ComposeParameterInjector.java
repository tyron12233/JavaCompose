package org.example;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;

import static com.sun.source.tree.Tree.*;

public class ComposeParameterInjector extends TreeTranslator {

    // Compose constants
    private static final int BITS_PER_PARAM = 3;
    private static final int SLOTS_PER_INT = 10; // 32 bits / 3 bits = 10 params max

    private final TreeMaker treeMaker;
    private final Names names;

    public ComposeParameterInjector(Context context) {
        treeMaker = TreeMaker.instance(context);
        names = Names.instance(context);
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        if (isComposableCandidate(tree)) {
            ListBuffer<JCExpression> newArgs = new ListBuffer<>();
            newArgs.appendList(tree.args);

            JCTree.JCExpression composerArg = treeMaker.Ident(names.fromString("$composer"));
            newArgs.append(composerArg);

            // calculate and append changed bitmasks
            // even if 0 args, we need at least one changed param (usually 0).
            int paramCount = tree.args.size();
            int numChangeParams = Math.max(1, (int) Math.ceil((double) paramCount / SLOTS_PER_INT));

            for (int i = 0; i < numChangeParams; i++) {
                int mask = calculateBitmaskForChunk(tree.args, i);
                newArgs.append(treeMaker.Literal(TypeTag.INT, mask));
            }

            tree.args = newArgs.toList();
        }

        super.visitApply(tree);
    }

    /**
     * Checks if a method invocation is a Compose call.
     * We currently run before the Attribution phase so types are not yet
     * available. We rely (for now) on naming convention that Compose
     * functions start with an uppercase letter
     */
    private boolean isComposableCandidate(JCMethodInvocation tree) {
        if (tree.meth.getKind() == Kind.IDENTIFIER) {
            Name name = ((JCIdent) tree.meth).getName();
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
        }

        return false;
    }


    /**
     * Calculates the bitmask for a specific chunk of 10 parameters.
     * @param args The full list of arguments.
     * @param chunkIndex Which integer we are calculating (0 for first 10, 1 for next 10, etc).
     */
    private int calculateBitmaskForChunk(List<JCTree.JCExpression> args, int chunkIndex) {
        int mask = 0;
        int startIndex = chunkIndex * SLOTS_PER_INT;

        // Ensure we don't go out of bounds
        int endIndex = Math.min(startIndex + SLOTS_PER_INT, args.size());

        for (int i = startIndex; i < endIndex; i++) {
            JCTree.JCExpression arg = args.get(i);
            int stability = determineStability(arg);

            // The shift is relative to the start of the chunk (0 to 9)
            int relativeIndex = i - startIndex;
            mask |= (stability << (relativeIndex * BITS_PER_PARAM));
        }

        return mask;
    }

    private int determineStability(JCTree.JCExpression arg) {
        // 0 = Unstable (Default for Lambdas)
        // 1 = Stable (Literals)

        if (arg.getKind() == Kind.LAMBDA_EXPRESSION) return 0;

        if (arg.getKind() == Kind.STRING_LITERAL ||
                arg.getKind() == Kind.INT_LITERAL ||
                arg.getKind() == Kind.BOOLEAN_LITERAL) {
            return 1;
        }

        return 0; // Default to dirty
    }
}
