package org.example;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;


public class Main {
    public static void main(String[] args) {

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        String code = """
                import androidx.compose.runtime.Composable;
                import androidx.compose.runtime.Composer;
                import androidx.compose.runtime.Scope;
                
                public class Main {
                
                    @Composable
                    void MyScreen(String text) {
                        Column {
                            Text(text);
                        }
                    }
                }
                """;

        TestFileObject test = TestFileObject.fromSource("Main", code);


        BasicJavacTask task = (BasicJavacTask) compiler.getTask(
                Writer.nullWriter(),
                compiler.getStandardFileManager(
                        diagnostic -> {
                            System.out.println(diagnostic.toString());
                        },
                        Locale.getDefault(),
                        Charset.defaultCharset()
                ),
                diagnostic -> {
                },
                null, null,
                List.of(test)
        );

        List<TreeTranslator> passes = List.of(
                new KotlinSyntaxFixer(task.getContext()),
                new ComposableDefinitionTransformer(task.getContext()),
                new ComposeGroupTransformer(task.getContext()),
                new ComposeParameterInjector(task.getContext()),
                new ComposableBodyTransformer(task.getContext())
        );


        Iterable<? extends CompilationUnitTree> parse = task.parse();
        JCTree root = (JCTree) parse.iterator().next();

        passes.forEach(it -> it.translate(root));

        task.analyze();

        System.out.println("DONE!");
        System.out.println(root);
    }

    static class TestFileObject extends SimpleJavaFileObject {

        private final String source;

        public static TestFileObject fromSource(String name, String source) {
            return new TestFileObject(
                    URI.create("string:///" + name.replace('.', '/')),
                    Kind.SOURCE,
                    source
            );
        }


        /**
         * Construct a SimpleJavaFileObject of the given kind and with the
         * given URI.
         *
         * @param uri  the URI for this file object
         * @param kind the kind of this file object
         */
        protected TestFileObject(URI uri, Kind kind, String source) {
            super(uri, kind);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return source;
        }
    }
}