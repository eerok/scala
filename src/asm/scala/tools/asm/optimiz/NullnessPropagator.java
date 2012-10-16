/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.asm.optimiz;

import java.util.List;

import scala.tools.asm.Opcodes;
import scala.tools.asm.Type;

import scala.tools.asm.tree.MethodNode;
import scala.tools.asm.tree.AbstractInsnNode;
import scala.tools.asm.tree.InvokeDynamicInsnNode;
import scala.tools.asm.tree.MethodInsnNode;

import scala.tools.asm.tree.analysis.AnalyzerException;
import scala.tools.asm.tree.analysis.Analyzer;
import scala.tools.asm.tree.analysis.Frame;
import scala.tools.asm.tree.analysis.Value;

/**
 *  Infers null resp. non-null reaching certain program points, simplifying control-flow based on that.
 *
 *  @author  Miguel Garcia, http://lamp.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/
 *  @version 1.0
 */
public class NullnessPropagator {

    /** after transform() has run, this field records whether
     *  at least one pass of this transformer modified something. */
    public boolean changed = false;

    public void transform(final String owner, final MethodNode mnode) throws AnalyzerException {

        NullnessAnalyzer nany    = NullnessAnalyzer.create();
        Frame[] frames           = nany.analyze(owner, mnode);
        AbstractInsnNode[] insns = mnode.instructions.toArray();

        changed = false;

        int i = 0;
        while(i < frames.length) {
            if (true) {
                ;
                changed = false; // TODO
            }
            i += 1;
        }

    }


    public enum Nullness {
        INDOUBT_STATUS,
        NONNULL_STATUS,
        NULL_STATUS
    }


    /**
     *  Additionally to what SourceValue tracks (instructions that may produce this value),
     *  a StatusValue tracks its null vs. non-null vs. in-doubt status.
     * */
    static private class StatusValue implements Value {

        private int size = 0;
        private Nullness status = Nullness.INDOUBT_STATUS;

        public StatusValue(final int size) {
            this.size   = size;
            this.status = Nullness.INDOUBT_STATUS;
        }

        public StatusValue(final int size, Nullness status) {
            this.size   = size;
            this.status = status;
        }

        public int getSize() {
            return size;
        }

        public boolean isNull()    { return status == Nullness.NULL_STATUS;    }
        public boolean isNonNull() { return status == Nullness.NONNULL_STATUS; }
        public boolean isIndoubt() { return status == Nullness.INDOUBT_STATUS; }

        public StatusValue checkedNULL()    { return new StatusValue(this.size, Nullness.NULL_STATUS);    }
        public StatusValue checkedNONNULL() { return new StatusValue(this.size, Nullness.NONNULL_STATUS); }

        @Override
        public boolean equals(final Object value) {
            if (!(value instanceof StatusValue)) {
                return false;
            }
            StatusValue v = (StatusValue) value;
            return (size == v.size) && (status == v.status);
        }

        @Override
        public int hashCode() {
            return size + status.hashCode();
        }

    } // end of class StatusValue


    static public class StatusInterpreter extends SizingInterpreter<StatusValue> {

        public StatusInterpreter() {
            super(ASM4);
        }

        protected StatusInterpreter(final int api) {
            super(api);
        }

        @Override
        public StatusValue newValue(final Type type) {
            if (type == Type.VOID_TYPE) {
                return null;
            }
            return new StatusValue(type == null ? 1 : type.getSize());
        }

        public StatusValue createStatusValue(final int size) {
            assert size == 1 || size == 2;
            return new StatusValue(size);
        }

        @Override
        public StatusValue newOperation(final AbstractInsnNode insn) {
            StatusValue sv = createStatusValue(getResultSize(insn));
            if (insn.getOpcode() == Opcodes.ACONST_NULL) {
                return sv.checkedNULL();
            }
            return sv;
        }

        @Override
        public StatusValue copyOperation(final AbstractInsnNode insn, final StatusValue value) {
            return value;
        }

        @Override
        public StatusValue unaryOperation(final AbstractInsnNode insn, final StatusValue value) {
            StatusValue sv = createStatusValue(getResultSize(insn));
            switch (insn.getOpcode()) {
                case NEWARRAY:
                case ANEWARRAY:
                    return sv.checkedNONNULL();
                default:
                    return sv;
            }
        }

        @Override
        public StatusValue binaryOperation(
            final AbstractInsnNode insn,
            final StatusValue value1,
            final StatusValue value2)
        {
            return createStatusValue(getResultSize(insn));
        }

        @Override
        public StatusValue ternaryOperation(
            final AbstractInsnNode insn,
            final StatusValue value1,
            final StatusValue value2,
            final StatusValue value3)
        {
            return createStatusValue(1);
        }

        @Override
        public StatusValue naryOperation(final AbstractInsnNode insn, final List<? extends StatusValue> values) {
            int size;
            int opcode = insn.getOpcode();
            if (opcode == MULTIANEWARRAY) {
                size = 1;
            } else {
                String desc = (opcode == INVOKEDYNAMIC)?
                        ((InvokeDynamicInsnNode) insn).desc:
                        ((MethodInsnNode) insn).desc;
                size = Type.getReturnType(desc).getSize();
            }
            StatusValue sv = createStatusValue(size);

            switch (insn.getOpcode()) {
                case MULTIANEWARRAY:
                    return sv.checkedNONNULL();
                case INVOKEVIRTUAL:
                case INVOKESPECIAL:
                case INVOKEINTERFACE:
                    if(neverReturnsNull((MethodInsnNode) insn)) {
                        return sv.checkedNONNULL();
                    } else {
                        return sv;
                    }
                default:
                    return sv;
            }

        }

        @Override
        public void returnOperation(
            final AbstractInsnNode insn,
            final StatusValue value,
            final StatusValue expected)
        {
        }

        @Override
        public StatusValue merge(final StatusValue d, final StatusValue w) {

            if(d == w) {
                return d;
            }
            assert d.size == w.size;
            // this ensures the updated sv.status won't be seen from aliased references if any.
            StatusValue sv = new StatusValue(d.size);
            if (d.isNonNull() && w.isNonNull()) {
                sv.status = Nullness.NONNULL_STATUS;
            } else if (d.isNull() && w.isNull()) {
                sv.status = Nullness.NULL_STATUS;
            } else {
                assert sv.isIndoubt();
            }

            return sv;
        }

        /**
         *  Provided the call given by the argument completes normally,
         *  does that call always return a non-null reference?
         * */
        private boolean neverReturnsNull(MethodInsnNode callsite) {
            if(Util.isJavaBoxCall(callsite))     return true;
            if(SSLUtil.isScalaBoxCall(callsite)) return true;
            return false;
        }

    } // end of class StatusInterpreter



    static private class NullnessAnalyzer extends Analyzer<StatusValue> {

        public static NullnessAnalyzer create() {
            return new NullnessAnalyzer(new StatusInterpreter());
        }

        public NullnessAnalyzer(final StatusInterpreter interpreter) {
            super(interpreter);
        }

        MethodNode mnode = null;
        Frame<StatusValue>[] frames = null;

        @Override
        public Frame[] analyze(final String owner, final MethodNode mnode) throws AnalyzerException {
            this.mnode = mnode;
            frames = super.analyze(owner, mnode);
            return frames;
        }

        public Frame<StatusValue> frameAt(AbstractInsnNode insn) {
            int idx = mnode.instructions.indexOf(insn);
            return frames[idx];
        }

        @Override
        protected StatusValue newLocal(Frame current0, boolean isInstanceMethod, int idx, Type type) {
            assert(type != Type.VOID_TYPE);
            StatusValue sv = new StatusValue(type.getSize());
            if(isInstanceMethod && idx == 0) {
                sv.status = Nullness.NONNULL_STATUS;
            }
            return sv;
        }

        // TODO newException()

    } // end of nested class NullnessAnalyzer


} // end of class NullnessPropagator