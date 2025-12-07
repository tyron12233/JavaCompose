package org.example;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

public class ComposableDefinitionTransformer extends TreeTranslator {

    private final TreeMaker treeMaker;
    private final Names names;

    public ComposableDefinitionTransformer(Context context) {
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl tree) {
        if (isComposableCandidate(tree)) {
            JCTree.JCVariableDecl composerParam = treeMaker.VarDef(
                    treeMaker.Modifiers(Flags.PARAMETER),
                    names.fromString("$composer"),
                    treeMaker.Ident(names.fromString("Composer")),
                    null
            );

            // create int $changed parameter
            JCTree.JCVariableDecl changedParam = treeMaker.VarDef(
                    treeMaker.Modifiers(Flags.PARAMETER),
                    names.fromString("$changed"),
                    treeMaker.TypeIdent(TypeTag.INT),
                    null
            );

            // append to existing parameters
            // Handle chunking (changed1, changed2) if params > 10 here as well.
            tree.params = tree.params.append(composerParam).append(changedParam);

            // update return type?
            // composables usually return void, but if not, logic is same.
        }

        super.visitMethodDef(tree);
    }

    private boolean isComposableCandidate(JCTree.JCMethodDecl tree) {
        String name = tree.name.toString();
        // skip constructors (<init>) and lowercase methods
        return !name.equals("<init>") && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }
}
