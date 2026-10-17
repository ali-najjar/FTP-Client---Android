import java.io.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Removes MethodParameters attributes from every class in a jar. The d8 build
 * bundled for offline builds crashes on parameters without a name (as javac
 * emits for synthetic enum constructor parameters with -parameters).
 * Usage: java -cp asm.jar StripParams.java in.jar out.jar
 */
public class StripParams {
    public static void main(String[] args) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                byte[] data = in.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                                @Override
                                public void visitParameter(String n, int a) { }
                            };
                        }
                    }, 0);
                    data = writer.toByteArray();
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
    }
}
