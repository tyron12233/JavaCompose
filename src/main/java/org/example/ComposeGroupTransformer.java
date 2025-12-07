package org.example;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import javax.tools.JavaFileObject;

public class ComposeGroupTransformer extends TreeTranslator {

    private final TreeMaker treeMaker;
    private final Names names;

    private int currentFileHash = 0;

    public ComposeGroupTransformer(Context context) {
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public void visitTopLevel(JCTree.JCCompilationUnit tree) {
        JavaFileObject sourceFile = tree.getSourceFile();
        if (sourceFile != null) {
            String path = sourceFile.getName();
            this.currentFileHash = path.hashCode();
        } else {
            this.currentFileHash = 0;
        }

        super.visitTopLevel(tree);
    }

    @Override
    public void visitLambda(JCTree.JCLambda tree) {
        if (tree.body instanceof JCTree.JCBlock) {
            JCTree.JCBlock bodyBlock = (JCTree.JCBlock) tree.body;

            // formula: (FileHash * 31) + ByteOffset
            // this ensures that if the file content stays the same, the key stays the same.
            int offset = tree.pos; // This works because Pass 1 set the position
            int groupKey = (currentFileHash * 31) + offset;

            JCTree.JCStatement startGroup = createComposerCall(
                    "startReplaceableGroup",
                    treeMaker.Literal(TypeTag.INT, groupKey)
            );

            JCTree.JCStatement endGroup = createComposerCall("endReplaceableGroup");

            // 3. Inject
            ListBuffer<JCTree.JCStatement> newStats = new ListBuffer<>();
            newStats.append(startGroup);
            newStats.appendList(bodyBlock.stats);
            newStats.append(endGroup);

            bodyBlock.stats = newStats.toList();
        }

        super.visitLambda(tree);
    }

    /**
     * Helper to create `composer.methodName(args)`
     */
    private JCTree.JCStatement createComposerCall(String methodName, JCTree.JCExpression... args) {
        JCTree.JCExpression composerIdent = treeMaker.Ident(names.fromString("$composer"));

        // Select the method "composer.methodName"
        JCTree.JCFieldAccess select = treeMaker.Select(composerIdent, names.fromString(methodName));

        com.sun.tools.javac.util.List<JCTree.JCExpression> argList =
                com.sun.tools.javac.util.List.from(args);

        JCTree.JCMethodInvocation call = treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                select,
                argList
        );

        return treeMaker.Exec(call);
    }
}
