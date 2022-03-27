package org.quiltmc.enigmaplugin.index;

import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodecIndex implements Opcodes {
    private static final List<MethodInfo> CODEC_FIELD_METHODS = List.of(
            new MethodInfo("com/mojang/serialization/codecs/PrimitiveCodec", "fieldOf", "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;"),     
            new MethodInfo("com/mojang/serialization/Codec", "fieldOf", "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;")
    );
    private static final List<MethodInfo> CODEC_OPTIONAL_FIELD_METHODS = List.of(
            new MethodInfo("com/mojang/serialization/codecs/PrimitiveCodec", "optionalFieldOf", "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;"),
            new MethodInfo("com/mojang/serialization/codecs/PrimitiveCodec", "optionalFieldOf", "(Ljava/lang/String;Ljava/lang/Object;)Lcom/mojang/serialization/MapCodec;"),
            new MethodInfo("com/mojang/serialization/Codec", "optionalFieldOf", "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;"),
            new MethodInfo("com/mojang/serialization/Codec", "optionalFieldOf", "(Ljava/lang/String;Ljava/lang/Object;)Lcom/mojang/serialization/MapCodec;")
    );
    private static final MethodInfo FOR_GETTER_METHOD = new MethodInfo("com/mojang/serialization/MapCodec", "forGetter", "(Ljava/util/function/Function;)Lcom/mojang/serialization/codecs/RecordCodecBuilder;");
    private final Analyzer<SourceValue> analyzer;

    private final Map<FieldEntry, String> fieldNames = new HashMap<>();
    private final Map<MethodEntry, String> methodNames = new HashMap<>();

    public CodecIndex() {
        analyzer = new Analyzer<>(new SourceInterpreter());
    }

    private static boolean isCodecFieldMethod(MethodInsnNode mInsn) {
        return CODEC_FIELD_METHODS.stream().anyMatch(m -> m.matches(mInsn)) || CODEC_OPTIONAL_FIELD_METHODS.stream().anyMatch(m -> m.matches(mInsn));
    }

    private static String toCamelCase(String s) {
        while (s.contains("_")) {
            s = s.replaceFirst("_[a-z]", String.valueOf(Character.toUpperCase(s.charAt(s.indexOf('_') + 1))));
        }
        return s;
    }

    public void visitClassNode(ClassNode node) {
        for (MethodNode method : node.methods) {
            try {
                visitMethodNode(node, method);
            } catch (Exception e) {
                System.err.println("Error visiting method " + method.name + method.desc + " in class " + node.name);
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private void visitMethodNode(ClassNode parent, MethodNode node) throws AnalyzerException {
        Frame<SourceValue>[] frames = analyzer.analyze(parent.name, node);
        InsnList instructions = node.instructions;
        for (int i = 1; i < instructions.size() && i < frames.length - 1; i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof MethodInsnNode mInsn && isCodecFieldMethod((MethodInsnNode) insn)) {
                // System.out.println(mInsn.getOpcode() + " " + mInsn.owner + "." + mInsn.name + " " + mInsn.desc + " in " + parent.name + "." + node.name + " " + node.desc + " (" + i + ")");
                Frame<SourceValue> frame = frames[i];

                // Find the field name in the stack
                String name = null;
                stackFor: for (int j = 0; j < frame.getStackSize(); j++) {
                    SourceValue value = frame.getStack(j);
                    for (AbstractInsnNode insn2 : value.insns) {
                        if (insn2 instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String) {
                            name = (String) ldcInsn.cst;
                            // System.out.println("Found name \"" + name + "\"");
                            break stackFor;
                        }
                    }
                }

                if (name == null) {
                    continue;
                }

                // Find a forGetter call
                for (int j = i + 2; j < instructions.size(); j++) {
                    Frame<SourceValue> frame2 = frames[j];
                    boolean hasCodec = false;
                    for (int k = 0; k < frame2.getStackSize(); k++) {
                        SourceValue value = frame2.getStack(k);
                        for (AbstractInsnNode insn2 : value.insns) {
                            if (insn2 == mInsn) {
                                hasCodec = true;
                                break;
                            }
                        }
                    }

                    if (!hasCodec) {
                        break;
                    }

                    AbstractInsnNode insn2 = instructions.get(j);
                    if (insn2 instanceof MethodInsnNode mInsn2 && FOR_GETTER_METHOD.matches(mInsn2)) {
                        // System.out.println("Found forGetter call " + mInsn2.getOpcode() + " (" + j + ")");
                        AbstractInsnNode getterInsn = instructions.get(j - 1);
                        if (!(getterInsn instanceof InvokeDynamicInsnNode getterInvokeInsn)) {
                            continue;
                        }

                        // System.out.println("Found getter call " + getterInvokeInsn.bsm + " (" + (j - 1) + ")");
                        visitGetterInvokeDynamicInsn(parent, getterInvokeInsn, name);
                        break;
                    }
                }
            }
        }
    }

    private void visitGetterInvokeDynamicInsn(ClassNode parent, InvokeDynamicInsnNode insn, String name) {
        if (insn.bsmArgs.length != 3) {
            return;
        }

        Handle getterHandle = (Handle) insn.bsmArgs[1];
        if (!getterHandle.getOwner().equals(parent.name)) {
            return;
        }

        ClassEntry parentEntry = new ClassEntry(parent.name);
        String camelCaseName = toCamelCase(name);
        String getterName = "get" + camelCaseName.substring(0, 1).toUpperCase() + camelCaseName.substring(1);
        if (getterHandle.getTag() == H_INVOKEVIRTUAL) {
            MethodEntry entry = new MethodEntry(parentEntry, getterHandle.getName(), new MethodDescriptor(getterHandle.getDesc()));
            methodNames.put(entry, getterName);
        } else if (getterHandle.getTag() == H_INVOKESTATIC) {
            MethodNode method = null;
            for (MethodNode m : parent.methods) {
                if (m.name.equals(getterHandle.getName()) && m.desc.equals(getterHandle.getDesc())) {
                    method = m;
                    break;
                }
            }

            if (method == null) {
                return;
            }

            FieldInsnNode fieldInsn = null;
            MethodInsnNode methodInsn = null;
            InsnList instructions = method.instructions;
            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode insn2 = instructions.get(i);
                if (insn2 instanceof FieldInsnNode fInsn && fInsn.owner.equals(parent.name)) {
                    fieldInsn = fieldInsn == null ? (FieldInsnNode) insn2 : null;
                } else if (insn2 instanceof MethodInsnNode mInsn && mInsn.owner.equals(parent.name)) {
                    methodInsn = methodInsn == null ? (MethodInsnNode) insn2 : null;
                }
            }

            if (fieldInsn != null) {
                FieldEntry entry = new FieldEntry(parentEntry, fieldInsn.name, new TypeDescriptor(fieldInsn.desc));
                fieldNames.put(entry, camelCaseName);
            } else if (methodInsn != null) {
                MethodEntry entry = new MethodEntry(parentEntry, methodInsn.name, new MethodDescriptor(methodInsn.desc));
                methodNames.put(entry, getterName);
            }
        }
    }

    public boolean hasField(FieldEntry field) {
        return fieldNames.containsKey(field);
    }

    public boolean hasMethod(MethodEntry method) {
        return methodNames.containsKey(method);
    }

    public String getFieldName(FieldEntry field) {
        return fieldNames.get(field);
    }

    public String getMethodName(MethodEntry method) {
        return methodNames.get(method);
    }

    record MethodInfo(String owner, String name, String desc) {
        public boolean matches(MethodInsnNode insn) {
            return insn.owner.equals(owner) && insn.name.equals(name) && insn.desc.equals(desc);
        }
    }
}
