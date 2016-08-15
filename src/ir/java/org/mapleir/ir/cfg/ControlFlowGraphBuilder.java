package org.mapleir.ir.cfg;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.tree.AbstractInsnNode.*;

import java.util.*;
import java.util.Map.Entry;

import org.mapleir.ir.code.ExpressionStack;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.expr.*;
import org.mapleir.ir.code.expr.ArithmeticExpression.Operator;
import org.mapleir.ir.code.expr.ComparisonExpression.ValueComparisonType;
import org.mapleir.ir.code.stmt.*;
import org.mapleir.ir.code.stmt.ConditionalJumpStatement.ComparisonType;
import org.mapleir.ir.code.stmt.MonitorStatement.MonitorMode;
import org.mapleir.ir.code.stmt.copy.AbstractCopyStatement;
import org.mapleir.ir.code.stmt.copy.CopyPhiStatement;
import org.mapleir.ir.code.stmt.copy.CopyVarStatement;
import org.mapleir.ir.locals.Local;
import org.mapleir.ir.locals.LocalsHandler;
import org.mapleir.ir.locals.VersionedLocal;
import org.mapleir.stdlib.cfg.edge.*;
import org.mapleir.stdlib.cfg.util.TypeUtils;
import org.mapleir.stdlib.cfg.util.TypeUtils.ArrayType;
import org.mapleir.stdlib.collections.NullPermeableHashMap;
import org.mapleir.stdlib.collections.SetCreator;
import org.mapleir.stdlib.collections.graph.FastGraphVertex;
import org.mapleir.stdlib.collections.graph.flow.ExceptionRange;
import org.mapleir.stdlib.collections.graph.flow.FlowGraph;
import org.mapleir.stdlib.collections.graph.flow.TarjanDominanceComputor;
import org.mapleir.stdlib.collections.graph.util.GraphUtils;
import org.mapleir.stdlib.ir.transform.impl.LivenessAnalyser;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

public class ControlFlowGraphBuilder {
	
	private static final int[] EMPTY_STACK_HEIGHTS = new int[]{};
	private static final int[] SINGLE_RETURN_HEIGHTS = new int[]{1};
	private static final int[] DOUBLE_RETURN_HEIGHTS = new int[]{2};
	
	private static final int[] DUP_HEIGHTS = new int[]{1};
	private static final int[] SWAP_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP_X1_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP2_32_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP2_X1_32_HEIGHTS = new int[]{1, 1, 1};
	private static final int[] DUP2_X1_64_HEIGHTS = new int[]{2, 1};
	private static final int[] DUP2_X2_64x64_HEIGHTS = new int[]{2, 2};
	private static final int[] DUP2_X2_64x32_HEIGHTS = new int[]{2, 1, 1};
	private static final int[] DUP2_X2_32x64_HEIGHTS = new int[]{1, 1, 2};
	private static final int[] DUP2_X2_32x32_HEIGHTS = new int[]{1, 1, 1, 1};
	private static final int[] DUP_X2_64_HEIGHTS = new int[]{1, 2};
	private static final int[] DUP_X2_32_HEIGHTS = new int[]{1, 1, 1};
	
	private final MethodNode method;
	private final ControlFlowGraph graph;
	private final BitSet finished;
	private final LinkedList<LabelNode> queue;
	private InsnList insns;
	private int count = 0;

	private Set<BasicBlock> updatedStacks;
	private BasicBlock currentBlock;
	private ExpressionStack currentStack;
	private boolean saved;
	
	// ssa
	private final Map<BasicBlock, Integer> insertion;
	private final Map<BasicBlock, Integer> process;
	private final Set<Local> locals;
	private final NullPermeableHashMap<Local, Set<BasicBlock>> assigns;
	private final Map<Local, Integer> counters;
	private final Map<Local, Stack<Integer>> stacks;
	private final Map<VersionedLocal, AbstractCopyStatement> defs;
	private TarjanDominanceComputor<BasicBlock> doms;
	private LivenessAnalyser liveness;
	
	private ControlFlowGraphBuilder(MethodNode method) {
		this.method = method;
		graph = new ControlFlowGraph(method, method.maxLocals);
		insns = method.instructions;
		/* a block can exist in the map in the graph 
		 * but not be populated yet.
		 * we do this so that when a flow function is reached, 
		 * we can create the block reference and then handle
		 * the creation mechanism later. */
		finished = new BitSet();
		queue = new LinkedList<>();
		
		updatedStacks = new HashSet<>();
		
		insertion = new HashMap<>();
		process = new HashMap<>();
		locals = new HashSet<>();
		assigns = new NullPermeableHashMap<>(new SetCreator<>());
		counters = new HashMap<>();
		stacks = new HashMap<>();
		defs = new HashMap<>();
	}
	
	void init() {
		entry(checkLabel());
		
		for(TryCatchBlockNode tc : method.tryCatchBlocks) {
			queue.addLast(tc.start);
			queue.addLast(tc.end);
			handler(tc);
		}
	}

	LabelNode checkLabel() {
		AbstractInsnNode first = insns.getFirst();
		if(!(first instanceof LabelNode)) {
			LabelNode nFirst = new LabelNode();
			insns.insertBefore(first, nFirst);
			first = nFirst;
		}
		return (LabelNode) first;
	}
	
	void entry(LabelNode firstLabel) {
		BasicBlock entry = makeBlock(firstLabel);
		entry.setInputStack(new ExpressionStack(1024 * 8));
		defineInputs(method, entry);
		graph.getEntries().add(entry);
		queue.add(firstLabel);
	}
	
	void handler(TryCatchBlockNode tc) {
		LabelNode label = tc.handler;
		BasicBlock handler = resolveTarget(label);
		if(handler.getInputStack() != null) {
			return;
		}
		
		ExpressionStack stack = new ExpressionStack(1024 * 8);
		handler.setInputStack(stack);
		
		Expression expr = new CaughtExceptionExpression(tc.type);
		Type type = expr.getType();
		VarExpression var = _var_expr(0, type, true);
		CopyVarStatement stmt = copy(var, expr);
		handler.add(stmt);
		
		stack.push(load_stack(0, type));
		
		queue.add(label);
		updatedStacks.add(handler);
	}
	
	void defineInputs(MethodNode m, BasicBlock b) {
		Type[] args = Type.getArgumentTypes(m.desc);
		int index = 0;
		if((m.access & Opcodes.ACC_STATIC) == 0) {
			addEntry(index, Type.getType(m.owner.name), b);
			index++;
		}
	
		for(int i=0; i < args.length; i++) {
			Type arg = args[i];
			addEntry(index, arg, b);
			index += arg.getSize();
		}
	}
	
	void addEntry(int index, Type type, BasicBlock b) {
		VarExpression var = _var_expr(index, type, false);
		CopyVarStatement stmt = selfDefine(var);
		assigns.getNonNull(var.getLocal()).add(b);
		b.add(stmt);
	}
	
	CopyVarStatement selfDefine(VarExpression var) {
		return new CopyVarStatement(var, var, true);
	}
	
	BasicBlock makeBlock(LabelNode label) {
		BasicBlock b = new BasicBlock(graph, ++count, label);
		queue.add(label);
		graph.addVertex(b);
		return b;
	}
	
	BasicBlock resolveTarget(LabelNode label) {
		BasicBlock block = graph.getBlock(label);
		if(block == null) {
			block = makeBlock(label);
		}
		return block;
	}
	
	void preprocess(BasicBlock block) {
		ExpressionStack stack = block.getInputStack().copy();
		updatedStacks.add(block);
		
		currentBlock = block;
		currentStack = stack;
		saved = false;
	}
	
	void process(LabelNode label) {
		/* it may not be properly initialised yet, however. */
		BasicBlock block = graph.getBlock(label);
		
		/* if it is, we don't need to process it. */
		if(block != null && finished.get(block.getNumericId())) {
			return;
		} else if(block == null) {
			block = makeBlock(label);
		} else {
			// i.e. not finished.
		}
		
		preprocess(block);
		
		/* populate instructions. */
		int codeIndex = insns.indexOf(label);
		finished.set(block.getNumericId());
		while(codeIndex <= insns.size()) {
			AbstractInsnNode ain = insns.get(++codeIndex);
			int type = ain.type();
			
			if(ain.opcode() != -1) {
				process(block, ain);
			}
			
			if(type == LABEL) {
				// split into new block
				BasicBlock immediate = resolveTarget((LabelNode) ain);
				graph.addEdge(block, new ImmediateEdge<>(block, immediate));
				break;
			} else  if(type == JUMP_INSN) {
				JumpInsnNode jin = (JumpInsnNode) ain;
				BasicBlock target = resolveTarget(jin.label);
				
				if(jin.opcode() == JSR) {
					throw new UnsupportedOperationException("jsr " + method);
				} else if(jin.opcode() == GOTO) {
					graph.addEdge(block, new UnconditionalJumpEdge<>(block, target, jin.opcode()));
				} else {
					graph.addEdge(block, new ConditionalJumpEdge<>(block, target, jin.opcode()));
					int nextIndex = codeIndex + 1;
					AbstractInsnNode nextInsn = insns.get(nextIndex);
					if(!(nextInsn instanceof LabelNode)) {
						LabelNode newLabel = new LabelNode();
						insns.insert(ain, newLabel);
						nextInsn = newLabel;
					}
					
					// create immediate successor reference if it's not already done
					BasicBlock immediate = resolveTarget((LabelNode) nextInsn);
					graph.addEdge(block, new ImmediateEdge<>(block, immediate));
				}
				break;
			} else if(type == LOOKUPSWITCH_INSN) {
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				
				for(int i=0; i < lsin.keys.size(); i++) {
					BasicBlock target = resolveTarget(lsin.labels.get(i));
					graph.addEdge(block, new SwitchEdge<>(block, target, lsin, lsin.keys.get(i)));
				}
				
				BasicBlock dflt = resolveTarget(lsin.dflt);
				graph.addEdge(block, new DefaultSwitchEdge<>(block, dflt, lsin));
				break;
			} else if(type == TABLESWITCH_INSN) {
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				for(int i=tsin.min; i <= tsin.max; i++) {
					BasicBlock target = resolveTarget(tsin.labels.get(i - tsin.min));
					graph.addEdge(block, new SwitchEdge<>(block, target, tsin, i));
				}
				BasicBlock dflt = resolveTarget(tsin.dflt);
				graph.addEdge(block, new DefaultSwitchEdge<>(block, dflt, tsin));
				break;
			} else if(isExitOpcode(ain.opcode())) {
				break;
			}
		}
		
		BasicBlock im = block.getImmediate();
		if (im != null && !queue.contains(im)) {
			update_target_stack(block, im, currentStack);
		}
	}
	
	static boolean isExitOpcode(int opcode) {
		switch(opcode) {
			case Opcodes.RET:
			case Opcodes.ATHROW:
			case Opcodes.RETURN:
			case Opcodes.IRETURN:
			case Opcodes.LRETURN:
			case Opcodes.FRETURN:
			case Opcodes.DRETURN:
			case Opcodes.ARETURN: {
				return true;
			}
			default: {
				return false;
			}
		}
	}
	
	void process(BasicBlock b, AbstractInsnNode ain) {
		int opcode = ain.opcode();
		switch (opcode) {
			case -1: {
				if (ain instanceof LabelNode)
					throw new IllegalStateException("Block should not contain label.");
				break;
			}
			case BIPUSH:
			case SIPUSH:
				_const(((IntInsnNode) ain).operand);
				break;
			case ACONST_NULL:
				_const(null);
				break;
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
				_const((int) (opcode - ICONST_M1) - 1);
				break;
			case LCONST_0:
			case LCONST_1:
				_const((long) (opcode - LCONST_0));
				break;
			case FCONST_0:
			case FCONST_1:
			case FCONST_2:
				_const((float) (opcode - FCONST_0));
				break;
			case DCONST_0:
			case DCONST_1:
				_const((long) (opcode - DCONST_0));
				break;
			case LDC:
				_const(((LdcInsnNode) ain).cst);
				break;
			case LCMP:
			case FCMPL:
			case FCMPG:
			case DCMPL:
			case DCMPG: {
				_compare(ValueComparisonType.resolve(opcode));
				break;
			}
			case NEWARRAY: {
				_new_array(
					new Expression[] { pop() }, 
					TypeUtils.getPrimitiveArrayType(((IntInsnNode) ain).operand)
				);
				break;
			}
			case ANEWARRAY: {
				_new_array(
					new Expression[] { pop() }, 
					Type.getType("[L" + ((TypeInsnNode) ain).desc + ";")
				);
				break;
			}
			case MULTIANEWARRAY: {
				MultiANewArrayInsnNode in = (MultiANewArrayInsnNode) ain;
				Expression[] bounds = new Expression[in.dims];
				for (int i = in.dims - 1; i >= 0; i--) {
					bounds[i] = pop();
				}
				_new_array(bounds, Type.getType(in.desc));
				break;
			}

			case RETURN:
				_return(Type.VOID_TYPE);
				break;
			case ATHROW:
				_throw();
				break;
				
			case MONITORENTER:
				_monitor(MonitorMode.ENTER);
				break;
			case MONITOREXIT:
				_monitor(MonitorMode.EXIT);
				break;
				
			case IRETURN:
			case LRETURN:
			case FRETURN:
			case DRETURN:
			case ARETURN:
				_return(Type.getReturnType(method.desc));
				break;
			case IADD:
			case LADD:
			case FADD:
			case DADD:
			case ISUB:
			case LSUB:
			case FSUB:
			case DSUB:
			case IMUL:
			case LMUL:
			case FMUL:
			case DMUL:
			case IDIV:
			case LDIV:
			case FDIV:
			case DDIV:
			case IREM:
			case LREM:
			case FREM:
			case DREM:
			
			case ISHL:
			case LSHL:
			case ISHR:
			case LSHR:
			case IUSHR:
			case LUSHR:
			
			case IAND:
			case LAND:
				
			case IOR:
			case LOR:
				
			case IXOR:
			case LXOR:
				_arithmetic(Operator.resolve(opcode));
				break;
			
			case INEG:
			case DNEG:
				_neg();
				break;
				
			case ARRAYLENGTH:
				_arraylength();
				break;
				
			case IALOAD:
			case LALOAD:
			case FALOAD:
			case DALOAD:
			case AALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
				_load_array(ArrayType.resolve(opcode));
				break;
				
			case IASTORE:
			case LASTORE:
			case FASTORE:
			case DASTORE:
			case AASTORE:
			case BASTORE:
			case CASTORE:
			case SASTORE:
				_store_array(ArrayType.resolve(opcode));
				break;
				
			case POP:
				_pop(1);
				break;
			case POP2:
				_pop(2);
				break;
				
			case DUP:
				_dup();
				break;
			case DUP_X1:
				_dup_x1();
				break;
			case DUP_X2:
				_dup_x2();
				break;

			case DUP2:
				_dup2();
				break;
			case DUP2_X1:
				_dup2_x1();
				break;
			case DUP2_X2:
				_dup2_x2();
				break;
				
			case SWAP:
				_swap();
				break;
				
			case I2L:
			case I2F:
			case I2D:
			case L2I:
			case L2F:
			case L2D:
			case F2I:
			case F2L:
			case F2D:
			case D2I:
			case D2L:
			case D2F:
			case I2B:
			case I2C:
			case I2S:
				_cast(TypeUtils.getCastType(opcode));
				break;
			case CHECKCAST:
				_cast(Type.getType("L" + ((TypeInsnNode)ain).desc + ";"));
				break;
			case INSTANCEOF:
				_instanceof(Type.getType("L" + ((TypeInsnNode)ain).desc + ";"));
				break;
			case NEW:
				_new(Type.getType("L" + ((TypeInsnNode)ain).desc + ";"));
				break;
				
			case INVOKEDYNAMIC:
				throw new UnsupportedOperationException("INVOKEDYNAMIC");
			case INVOKEVIRTUAL:
			case INVOKESTATIC:
			case INVOKESPECIAL:
			case INVOKEINTERFACE:
				MethodInsnNode min = (MethodInsnNode) ain;
				_call(opcode, min.owner, min.name, min.desc);
				break;
				
			case ILOAD:
			case LLOAD:
			case FLOAD:
			case DLOAD:
			case ALOAD:
				_load(((VarInsnNode) ain).var, TypeUtils.getLoadType(opcode));
				break;
				
			case ISTORE:
			case LSTORE:
			case FSTORE:
			case DSTORE:
			case ASTORE:
				_store(((VarInsnNode) ain).var, TypeUtils.getStoreType(opcode));
				break;
				
			case IINC:
				IincInsnNode iinc = (IincInsnNode) ain;
				_inc(iinc.var, iinc.incr);
				break;
				
			case PUTFIELD:
			case PUTSTATIC: {
				FieldInsnNode fin = (FieldInsnNode) ain;
				_store_field(opcode, fin.owner, fin.name, fin.desc);
				break;
			}
			case GETFIELD:
			case GETSTATIC:
				FieldInsnNode fin = (FieldInsnNode) ain;
				_load_field(opcode, fin.owner, fin.name, fin.desc);
				break;
				
			case TABLESWITCH: {
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				LinkedHashMap<Integer, BasicBlock> targets = new LinkedHashMap<>();
				for(int i=tsin.min; i <= tsin.max; i++) {
					BasicBlock targ = resolveTarget(tsin.labels.get(i - tsin.min));
					targets.put(i, targ);
				}
				_switch(targets, resolveTarget(tsin.dflt));
				break;
			}
			
			case LOOKUPSWITCH: {
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				LinkedHashMap<Integer, BasicBlock> targets = new LinkedHashMap<>();
				for(int i=0; i < lsin.keys.size(); i++) {
					int key = lsin.keys.get(i);
					BasicBlock targ = resolveTarget(lsin.labels.get(i));
					targets.put(key, targ);
				}
				_switch(targets, resolveTarget(lsin.dflt));
				break;
			}
			
			case GOTO:
				_jump_uncond(resolveTarget(((JumpInsnNode) ain).label));
				break;
			case IFNULL:
			case IFNONNULL:
				_jump_null(resolveTarget(((JumpInsnNode) ain).label), opcode == IFNONNULL);
				break;
				
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
				_jump_compare(resolveTarget(((JumpInsnNode) ain).label), ComparisonType.getType(opcode));
				break;
				
			case IFEQ:
			case IFNE:
			case IFLT:
			case IFGE:
			case IFGT:
			case IFLE:
				_jump_cmp0(resolveTarget(((JumpInsnNode) ain).label), ComparisonType.getType(opcode));
				break;
		}
	}
	
	void _nop() {

	}

	void _const(Object o) {
		Expression e = new ConstantExpression(o);
		int index = currentStack.height();
		Type type = assign_stack(index, e);
		push(load_stack(index, type));
	}

	void _compare(ValueComparisonType ctype) {
		Expression right = pop();
		Expression left = pop();
		push(new ComparisonExpression(left, right, ctype));
	}

	void _return(Type type) {
		if (type == Type.VOID_TYPE) {
			currentStack.assertHeights(EMPTY_STACK_HEIGHTS);
			addStmt(new ReturnStatement());
		} else {
			if(type.getSize() == 2) {
				currentStack.assertHeights(DOUBLE_RETURN_HEIGHTS);
			} else {
				currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
			}
			addStmt(new ReturnStatement(type, pop()));
		}
	}

	void _throw() {
		currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
		addStmt(new ThrowStatement(pop()));
	}

	void _monitor(MonitorMode mode) {
		addStmt(new MonitorStatement(pop(), mode));
	}

	void _arithmetic(Operator op) {
		push(new ArithmeticExpression(pop(), pop(), op));
	}
	
	void _neg() {
		push(new NegationExpression(pop()));
	}
	
	void _arraylength() {
		push(new ArrayLengthExpression(pop()));
	}
	
	void _load_array(ArrayType type) {
		// prestack: var1, var0 (height = 2)
		// poststack: var0
		// assignments: var0 = var0[var1]
		int height = currentStack.height();
		Expression index = pop();
		Expression array = pop();
		assign_stack(height - 2, new ArrayLoadExpression(array, index, type));
		push(load_stack(height - 2, type.getType()));
	}
	
	void _store_array(ArrayType type) {
		Expression value = pop();
		Expression index = pop();
		Expression array = pop();
		addStmt(new ArrayStoreStatement(array, index, value, type));
	}
	
	void _pop(int amt) {
		for(int i=0; i < amt; i++) {
			addStmt(new PopStatement(pop()));
		}
	}
	
	void _dup() {
		// prestack: var0 (height = 1)
		// poststack: var1, var0
		// assignments: var1 = var0(initial)
		currentStack.assertHeights(DUP_HEIGHTS);
		int baseHeight = currentStack.height();

		Expression var0 = pop();

		Type var1Type = assign_stack(baseHeight, var0); // var1 = var0
		push(load_stack(baseHeight - 1, var0.getType())); //  push var0
		push(load_stack(baseHeight, var1Type)); // push var1
	}

	void _dup_x1() {
		// prestack: var1, var0 (height = 2)
		// poststack: var2, var1, var0
		// assignments: var0 = var1(initial)
		// assignments: var1 = var0(initial)
		// assignments: var2 = var1(initial)
		currentStack.assertHeights(DUP_X1_HEIGHTS);
		int baseHeight = currentStack.height();

		Expression var1 = pop();
		Expression var0 = pop();

		Type var3Type = assign_stack(baseHeight + 1, var0); // var3 = var0

		Type var0Type = assign_stack(baseHeight - 2, var1); // var0 = var1(initial)
		Type var2Type = assign_stack(baseHeight + 0, var1); // var2 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 1, var3Type)); // var1 = var3 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
		push(load_stack(baseHeight + 0, var2Type)); // push var2
	}

	void _dup_x2() {
		int baseHeight = currentStack.height();

		if(currentStack.peek(1).getType().getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var1, var0
			// assignments: var0 = var2(initial)
			// assignments: var1 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP_X2_64_HEIGHTS);

			Expression var2 = pop();
			Expression var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)

			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var2); // var3 = var2(initial)
			Type var1Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var4Type)); // var1 = var4 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight + 0, var3Type)); // push var3
		} else {
			// prestack: var2, var1, var0 (height = 3)
			// poststack: var3, var2, var1, var0
			// assignments: var0 = var2(initial)
			// assignments: var1 = var0(initial)
			// assignments: var2 = var1(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP_X2_32_HEIGHTS);

			Expression var2 = pop();
			Expression var1 = pop();
			Expression var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)
			Type var5Type = assign_stack(baseHeight + 2, var1); // var5 = var1(initial)

			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var2); // var3 = var2(initial)
			Type var1Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var4Type)); // var1 = var4 = var0(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 2, var5Type)); // var2 = var5 = var1(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight + 0, var3Type)); // push var3
		}
	}

	void _dup2() {
		int baseHeight = currentStack.height();

		if(peek().getType().getSize() == 2) {
			// prestack: var0 (height = 2)
			// poststack: var2, var0
			// assignments: var2 = var0

			Expression var0 = pop();

			Type var2Type = assign_stack(baseHeight, var0); // var2 = var0
			push(load_stack(baseHeight - 2, var0.getType())); //  push var0
			push(load_stack(baseHeight, var2Type)); // push var2
		} else {
			// prestack: var1, var0 (height = 2)
			// poststack: var3, var2, var1, var0
			// assignments: var2 = var0(initial)
			// assignments: var3 = var1(initial)
			currentStack.assertHeights(DUP2_32_HEIGHTS);

			Expression var1 = pop();
			Expression var0 = pop();

			Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
			Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

			push(load_stack(baseHeight - 2, var0.getType())); // push var0
			push(load_stack(baseHeight - 1, var1.getType())); // push var1
			push(load_stack(baseHeight + 0, var2Type)); // push var2
			push(load_stack(baseHeight + 1, var3Type)); // push var3
		}
	}

	void _dup2_x1() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();

		if(topType.getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var2, var0
			// assignments: var0 = var2(initial)
			// assignemnts: var2 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP2_X1_64_HEIGHTS);

			Expression var2 = pop();
			Expression var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)

			Type var3Type = assign_stack(baseHeight - 0, var2); // var3 = var2(initial)
			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 1, var4Type)); // var2 = var4 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight - 0, var3Type)); // push var3
		} else {
			// prestack: var2, var1, var0 (height = 3)
			// poststack: var4, var3, var2, var1, var0
			// assignments: var0 = var1(initial)
			// assignments: var1 = var2(initial)
			// assignments: var2 = var0(initial)
			// assignments: var3 = var1(initial)
			// assignments: var4 = var2(initial)
			currentStack.assertHeights(DUP2_X1_32_HEIGHTS);

			Expression var2 = pop();
			Expression var1 = pop();
			Expression var0 = pop();

			Type var5Type = assign_stack(baseHeight + 2, var0); // var5 = var0(initial)

			Type var0Type = assign_stack(baseHeight - 3, var1); // var0 = var1(initial)
			Type var1Type = assign_stack(baseHeight - 2, var2); // var1 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var1); // var3 = var1(initial)
			Type var4Type = assign_stack(baseHeight + 1, var2); // var4 = var2(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 2, var5Type)); // var2 = var5 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight + 0, var3Type)); // push var3
			push(load_stack(baseHeight + 1, var4Type)); // push var4
		}
	}

	void _dup2_x2() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();
		if(topType.getSize() == 2) {
			Type bottomType = currentStack.peek(1).getType();
			if (bottomType.getSize() == 2) {
				// 64x64
				// prestack: var2, var0 (height = 4)
				// poststack: var4, var2, var0
				// assignments: var0 = var2(initial)
				// assignments: var2 = var0(initial)
				// assignments: var4 = var2(initial)
				currentStack.assertHeights(DUP2_X2_64x64_HEIGHTS);

				Expression var2 = pop();
				Expression var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var4Type = assign_stack(baseHeight - 0, var2); // var4 = var2(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0;
				push(load_stack(baseHeight - 2, var2Type)); // push var2;
				push(load_stack(baseHeight - 0, var4Type)); // push var4;
			} else {
				//64x32
				// prestack: var2, var1, var0 (height = 4)
				// poststack: var4, var3, var2, var0
				// assignments: var0 = var2(initial)
				// assignments: var2 = var0(initial)
				// assignments: var3 = var1(initial)
				// assignments: var4 = var2(initial)
				currentStack.assertHeights(DUP2_X2_64x32_HEIGHTS);

				Expression var2 = pop();
				Expression var1 = pop();
				Expression var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2
				Type var3Type = assign_stack(baseHeight - 1, var1); // var3 = var1
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var0

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight - 1, var3Type)); // push var3
				push(load_stack(baseHeight + 0, var4Type)); // push var4
			}
		} else {
			Type bottomType = currentStack.peek(2).getType();
			if (bottomType.getSize() == 2) {
				// 32x64
				// prestack: var3, var2, var0 (height = 4)
				// poststack: var5, var4, var2, var1, var0
				// assignments: var0 = var2(initial)
				// assignments: var1 = var3(initial)
				// assignments: var2 = var0(initial)
				// assignments: var4 = var2(initial)
				// assignments: var5 = var3(initial)
				currentStack.assertHeights(DUP2_X2_32x64_HEIGHTS);

				Expression var3 = pop();
				Expression var2 = pop();
				Expression var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var1Type = assign_stack(baseHeight - 3, var3); // var1 = var3(initial)
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2(initial)
				Type var5Type = assign_stack(baseHeight + 1, var3); // var5 = var3(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 3, var1Type)); // push var1
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight + 0, var4Type)); // push var4
				push(load_stack(baseHeight + 1, var5Type)); // push var5
			} else {
				// 32x32
				// prestack: var3, var2, var1, var0 (height = 4)
				// poststack: var5, var4, var3, var2, var1, var0
				// var0 = var2
				// var1 = var3
				// var2 = var0
				// var3 = var1
				// var4 = var2
				// var5 = var3
				currentStack.assertHeights(DUP2_X2_32x32_HEIGHTS);

				Expression var3 = pop();
				Expression var2 = pop();
				Expression var1 = pop();
				Expression var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)
				Type var7Type = assign_stack(baseHeight + 3, var1); // var7 = var1(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var1Type = assign_stack(baseHeight - 3, var3); // var1 = var3(initial)
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2(initial)
				Type var5Type = assign_stack(baseHeight + 1, var3); // var5 = var3(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)
				Type var3Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 3, var7Type)); // var3 = var7 = var1(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 3, var1Type)); // push var1
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight - 1, var3Type)); // push var3
				push(load_stack(baseHeight + 0, var4Type)); // push var4
				push(load_stack(baseHeight + 1, var5Type)); // push var5
			}
		}
	}
	
	void _swap() {
		// prestack: var1, var0 (height = 2)
		// poststack: var1, var0
		// assignments: var0 = var1 (initial)
		// assignments: var1 = var0 (initial)

		currentStack.assertHeights(SWAP_HEIGHTS);
		int baseHeight = currentStack.height();

		Expression var1 = pop();
		Expression var0 = pop();

		Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
		Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

		Type var0Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var3Type)); // var0 = var3 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 0, var2Type)); // var1 = var2 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
	}
	
	void _cast(Type type) {
		Expression e = new CastExpression(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	void _instanceof(Type type) {
		InstanceofExpression e = new InstanceofExpression(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	void _new(Type type) {
		int index = currentStack.height();
		UninitialisedObjectExpression e = new UninitialisedObjectExpression(type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	void _new_array(Expression[] bounds, Type type) {
		int index = currentStack.height();
		NewArrayExpression e = new NewArrayExpression(bounds, type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	void _call(int op, String owner, String name, String desc) {
		int argLen = Type.getArgumentTypes(desc).length + (op == INVOKESTATIC ? 0 : 1);
		Expression[] args = new Expression[argLen];
		for (int i = args.length - 1; i >= 0; i--) {
			args[i] = pop();
		}
		InvocationExpression callExpr = new InvocationExpression(op, args, owner, name, desc);
		if(callExpr.getType() == Type.VOID_TYPE) {
			addStmt(new PopStatement(callExpr));
		} else {
			int index = currentStack.height();
			Type type = assign_stack(index, callExpr);
			push(load_stack(index, type));
		}
	}
	
	void _switch(LinkedHashMap<Integer, BasicBlock> targets, BasicBlock dflt) {
		Expression expr = pop();
		
		for (Entry<Integer, BasicBlock> e : targets.entrySet()) {
			update_target_stack(currentBlock, e.getValue(), currentStack);
		}
		
		update_target_stack(currentBlock, dflt, currentStack);
		
		addStmt(new SwitchStatement(expr, targets, dflt));
	}

	void _store_field(int opcode, String owner, String name, String desc) {
		if(opcode == PUTFIELD) {
			Expression val = pop();
			Expression inst = pop();
			addStmt(new FieldStoreStatement(inst, val, owner, name, desc));
		} else if(opcode == PUTSTATIC) {
			Expression val = pop();
			addStmt(new FieldStoreStatement(null, val, owner, name, desc));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	void _load_field(int opcode, String owner, String name, String desc) {
		if(opcode == GETFIELD || opcode == GETSTATIC) {
			Expression inst = null;
			if(opcode == GETFIELD) {
				inst = pop();
			}
			FieldLoadExpression fExpr = new FieldLoadExpression(inst, owner, name, desc);
			int index = currentStack.height();
			Type type = assign_stack(index, fExpr);
			push(load_stack(index, type));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	void _store(int index, Type type) {
		Expression expr = pop();
		VarExpression var = _var_expr(index, type, false);
		addStmt(copy(var, expr));
	}

	void _load(int index, Type type) {
		VarExpression e = _var_expr(index, type, false);
		assign_stack(currentStack.height(), e);
		push(e);
	}

	void _inc(int index, int amt) {
		VarExpression load = _var_expr(index, Type.INT_TYPE, false);
		ArithmeticExpression inc = new ArithmeticExpression(new ConstantExpression(amt), load, Operator.ADD);
		VarExpression var = _var_expr(index, Type.INT_TYPE, false);
		addStmt(copy(var, inc));
	}
	
	CopyVarStatement copy(VarExpression v, Expression e) {
		assigns.getNonNull(v.getLocal()).add(currentBlock);
		return new CopyVarStatement(v, e);
	}
	
	VarExpression _var_expr(int index, Type type, boolean isStack) {
		Local l = graph.getLocals().get(index, isStack);
		locals.add(l);
		return new VarExpression(l, type);
	}
	
	// var[index] = expr
	Type assign_stack(int index, Expression expr) {
		Type type = expr.getType();
		VarExpression var = _var_expr(index, type, true);
		CopyVarStatement stmt = copy(var, expr);
		addStmt(stmt);
		return type;
	}
	
	Expression load_stack(int index, Type type) {
		return _var_expr(index, type, true);
	}
	
	void _jump_compare(BasicBlock target, ComparisonType type, Expression left, Expression right) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new ConditionalJumpStatement(left, right, target, type));
	}
	
	void _jump_compare(BasicBlock target, ComparisonType type) {
		Expression right = pop();
		Expression left = pop();
		_jump_compare(target, type, left, right);
	}
	
	void _jump_cmp0(BasicBlock target, ComparisonType type) {
		Expression left = pop();
		ConstantExpression right = new ConstantExpression(0);
		_jump_compare(target, type, left, right);
	}

	void _jump_null(BasicBlock target, boolean invert) {
		Expression left = pop();
		ConstantExpression right = new ConstantExpression(null);
		ComparisonType type = invert ? ComparisonType.NE : ComparisonType.EQ;
		
		_jump_compare(target, type, left, right);
	}

	void _jump_uncond(BasicBlock target) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new UnconditionalJumpStatement(target));
	}
	
	Expression pop() {
		return currentStack.pop();
	}
	
	Expression peek() {
		return currentStack.peek();
	}

	void push(Expression e) {
		currentStack.push(e);
	}
	
	void addStmt(Statement stmt) {
		currentBlock.add(stmt);
	}
	
	void save_stack() {
		if (!currentBlock.isEmpty() && currentBlock.getAt(currentBlock.size() - 1).canChangeFlow()) {
			throw new IllegalStateException("Flow instruction already added to block; cannot save stack");
		}
			
		int height = currentStack.height();
		while(height > 0) {
			int index = height - 1;
			Expression expr = currentStack.pop();
			Type type = assign_stack(index, expr);
			push(load_stack(index, type));
			
			height -= type.getSize();
		}
		
		saved = true;
	}

	boolean can_succeed(ExpressionStack s, ExpressionStack succ) {
		// quick check stack heights
		if (s.height() != succ.height()) {
			return false;
		}
		ExpressionStack c0 = s.copy();
		ExpressionStack c1 = succ.copy();
		while (c0.height() > 0) {
			Expression e1 = c0.pop();
			Expression e2 = c1.pop();
			if (!(e1.getOpcode() == Opcode.LOCAL_LOAD) || !(e2.getOpcode() == Opcode.LOCAL_LOAD)) {
				return false;
			}
			if (((VarExpression) e1).getIndex() != ((VarExpression) e2).getIndex()) {
				return false;
			}
			if (!e1.getType().getDescriptor().equals(e2.getType().getDescriptor())) {
				return false;
			}
		}
		return true;
	}
	
	void update_target_stack(BasicBlock b, BasicBlock target, ExpressionStack stack) {
		if(updatedStacks.contains(b) && !saved) {
			save_stack();
		}
		// called just before a jump to a successor block may
		// happen. any operations, such as comparisons, that
		// happen before the jump are expected to have already
		// popped the left and right arguments from the stack before
		// checking the merge state.
		if (!updatedStacks.contains(target)) {
			// unfinalised block found.
			// System.out.println("Setting target stack of " + target.getId() + " to " + stack);
			target.setInputStack(stack.copy());
			updatedStacks.add(target);

			queue.addLast(target.getLabelNode());
		} else if (!can_succeed(target.getInputStack(), stack)) {
			// if the targets input stack is finalised and
			// the new stack cannot merge into it, then there
			// is an error in the bytecode (verifier error).
			System.out.println("Current: " + stack + " in " + b.getId());
			System.out.println("Target : " + target.getInputStack() + " in " + target.getId());
			throw new IllegalStateException("Stack coherency mismatch into #" + target.getId());
		}
	}
	
	void makeRanges(List<BasicBlock> order) {
		Map<String, ExceptionRange<BasicBlock>> ranges = new HashMap<>();
		for(TryCatchBlockNode tc : method.tryCatchBlocks) {
			int start = graph.getBlock(tc.start).getNumericId();
			int end = graph.getBlock(tc.end).getNumericId() - 1;
			
			List<BasicBlock> range = GraphUtils.range(order, start, end);
			BasicBlock handler = graph.getBlock(tc.handler);
			String key = String.format("%s:%s:%s", BasicBlock.createBlockName(start), BasicBlock.createBlockName(end), handler.getId());
			
			ExceptionRange<BasicBlock> erange;
			if(ranges.containsKey(key)) {
				erange = ranges.get(key);
			} else {
				erange = new ExceptionRange<>(tc);
				erange.setHandler(handler);
				erange.addVertices(range);
				ranges.put(key, erange);
				
				if(!erange.isContiguous()) {
					System.out.println(erange + " not contiguous");
				}
				graph.addRange(erange);
			}
			
			erange.addType(tc.type);
			
			ListIterator<BasicBlock> lit = range.listIterator();
			while(lit.hasNext()) {
				BasicBlock block = lit.next();
				graph.addEdge(block, new TryCatchEdge<>(block, erange));
			}
		}
	}
	
	void processQueue() {
		while(!queue.isEmpty()) {
			LabelNode label = queue.removeFirst();
			process(label);
		}
		
		List<BasicBlock> blocks = new ArrayList<>(graph.vertices());
		Collections.sort(blocks, new Comparator<BasicBlock>() {
			@Override
			public int compare(BasicBlock o1, BasicBlock o2) {
				int i1 = insns.indexOf(o1.getLabelNode());
				int i2 = insns.indexOf(o2.getLabelNode());
				return Integer.compare(i1, i2);
			}
		});
		naturaliseGraph(blocks);
		makeRanges(blocks);
	}

	ControlFlowGraphBuilder build() {
		if(count == 0) { // no blocks created
			init();
			processQueue();
		}
		return this;
	}
	
	int mergeImmediates() {
		class MergePair {
			final BasicBlock src;
			final BasicBlock dst;
			final List<ExceptionRange<BasicBlock>> ranges;
			MergePair(BasicBlock src, BasicBlock dst, List<ExceptionRange<BasicBlock>> ranges)  {
				this.src = src;
				this.dst = dst;
				this.ranges = ranges;
			}
		}
		
		List<MergePair> merges = new ArrayList<>();
		Map<BasicBlock, BasicBlock> remap = new HashMap<>();
		
		for(BasicBlock b : graph.vertices()) {
			BasicBlock in = b.getIncomingImmediate();
			if(in == null) {
				continue;
			}
			Set<FlowEdge<BasicBlock>> inSuccs = in.getSuccessors(e -> !(e instanceof TryCatchEdge));
			if(inSuccs.size() != 1 || graph.getReverseEdges(b).size() != 1) {
				continue;
			}
			
			List<ExceptionRange<BasicBlock>> range1 = in.getProtectingRanges();
			List<ExceptionRange<BasicBlock>> range2 = in.getProtectingRanges();
			
			if(!range1.equals(range2)) {
				continue;
			}
			
			merges.add(new MergePair(in, b, range1));
			
			remap.put(in, in);
			remap.put(b, b);
		}
		
		for(MergePair p : merges) {
			BasicBlock src = remap.get(p.src);
			BasicBlock dst = p.dst;
			
			dst.transfer(src);
			
			for(FlowEdge<BasicBlock> e : graph.getEdges(dst)) {
				BasicBlock edst = e.dst;
				edst = remap.get(edst);
				graph.addEdge(src, e.clone(src, edst));
			}
			graph.removeVertex(dst);
			
			remap.put(dst, src);
			
			for(ExceptionRange<BasicBlock> r : p.ranges) {
				r.removeVertex(dst);
			}
			
			System.out.printf("Merged %s into %s.%n", dst.getId(), src.getId());
		}
		
		return merges.size();
	}
	
	/* static final Map<Class<?>, Integer> WEIGHTS = new HashMap<>();
	
	{
		WEIGHTS.put(ImmediateEdge.class, 10);
		WEIGHTS.put(ConditionalJumpEdge.class, 9);
		WEIGHTS.put(UnconditionalJumpEdge.class, 8);
		WEIGHTS.put(DefaultSwitchEdge.class, 7);
		WEIGHTS.put(SwitchEdge.class, 6);
		WEIGHTS.put(TryCatchEdge.class, 5);
	}  */
	
	class TarjanSCC <N extends FastGraphVertex> {
		final FlowGraph<N, FlowEdge<N>> graph;
		final Map<N, Integer> index;
		final Map<N, Integer> low;
		final LinkedList<N> stack;
		final List<List<N>> comps;
		int cur;
		
		TarjanSCC(FlowGraph<N, FlowEdge<N>> graph) {
			this.graph = graph;
			
			index = new HashMap<>();
			low = new HashMap<>();
			stack = new LinkedList<>();
			comps = new ArrayList<>();
		}

		/* List<FlowEdge<N>> weigh(Set<FlowEdge<N>> edges) {
			List<FlowEdge<N>> list = new ArrayList<>(edges);
			Collections.sort(list, new Comparator<FlowEdge<N>>() {
				@Override
				public int compare(FlowEdge<N> o1, FlowEdge<N> o2) {
					Class<?> c1 = o1.getClass();
					Class<?> c2 = o2.getClass();
					
					if(!WEIGHTS.containsKey(c1)) {
						throw new IllegalStateException(c1.toString());
					} else if(!WEIGHTS.containsKey(c2)) {
						throw new IllegalStateException(c2.toString());
					}
					
					int p1 = WEIGHTS.get(c1);
					int p2 = WEIGHTS.get(c2);
					
					// p2, p1 because higher weights are
					// more favoured.
					return Integer.compare(p2, p1);
				}
			});
			System.out.println("list: " + list);
			return list;
		} */
		
		void search(N n) {
			index.put(n, cur);
			low.put(n, cur);
			cur++;
			
			stack.push(n);
			
			for(FlowEdge<N> e : graph.getEdges(n)) {
				N s = e.dst;
				if(low.containsKey(s)) {
					low.put(n, Math.min(low.get(n), index.get(s)));
				} else {
					search(s);
					low.put(n, Math.min(low.get(n), low.get(s)));
				}
			}
			
			if(low.get(n) == index.get(n)) {
				List<N> c = new ArrayList<>();
				
				N w = null;
				do {
					w = stack.pop();
					c.add(0, w);
				} while (w != n);
				
				comps.add(0, bfs(n, c));
			}
		}
		
		List<N> bfs(N n, List<N> cand) {
			LinkedList<N> queue = new LinkedList<>();
			queue.add(n);
			
			List<N> bfs = new ArrayList<>();
			while(!queue.isEmpty()) {
				n = queue.pop();
				
				if(bfs.contains(n)) {
					continue;
				} else if(!cand.contains(n)) {
					System.out.println(n.getId() + " jumps out of component: " + cand);
					continue;
				}
				
				bfs.add(n);
				
				for(FlowEdge<N> e : graph.getEdges(n)) {
					N s = e.dst;
					queue.addLast(s);
				}
			}
			
			
			return bfs;
		}
	}
	
	void findComponents() {
		TarjanSCC<BasicBlock> scc = new TarjanSCC<>(graph);
		for(BasicBlock b : graph.vertices()) {
			if(!scc.low.containsKey(b)) {
				scc.search(b);
			}
		}
		
		List<BasicBlock> order = new ArrayList<>();
		for(List<BasicBlock> c : scc.comps) {
			order.addAll(c);
		}
		
		naturaliseGraph(order);
	}
	
	void naturaliseGraph(List<BasicBlock> order) {
		// copy edge sets
		Map<BasicBlock, Set<FlowEdge<BasicBlock>>> edges = new HashMap<>();
		for(BasicBlock b : order) {
			edges.put(b, graph.getEdges(b));
		}
		// clean graph
		graph.clear();
		
		// rename and add blocks
		int label = 1;
		for(BasicBlock b : order) {
			b.setId(label++);
			graph.addVertex(b);
		}
		
		for(Entry<BasicBlock, Set<FlowEdge<BasicBlock>>> e : edges.entrySet()) {
			BasicBlock b = e.getKey();
			for(FlowEdge<BasicBlock> fe : e.getValue()) {
				graph.addEdge(b, fe);
			}
		}
	}
	
	void insertPhis(BasicBlock b, Local l, int i, LinkedList<BasicBlock> queue) {
		if(b.getLabelNode() == null) {
			return; // exit
		}
		
		for(BasicBlock x : doms.iteratedFrontier(b)) {
			if(insertion.get(x) < i) {
				if(x.size() > 0 && graph.getReverseEdges(x).size() > 1) {
					Statement first = null;
					for(Statement stmt : x) {
						if(stmt.getOpcode() == Opcode.PHI_STORE) {
							continue;
						} else {
							first = stmt;
							break;
						}
					}
					// pruned SSA
					if(liveness.in(first).get(l)) {
						Map<BasicBlock, Expression> vls = new HashMap<>();
						int subscript = 0;
						for(FlowEdge<BasicBlock> fe : graph.getReverseEdges(x)) {
							vls.put(fe.src, new VarExpression(graph.getLocals().get(l.getIndex(), subscript++, l.isStack()), null));
						}
						PhiExpression phi = new PhiExpression(vls);
						CopyPhiStatement assign = new CopyPhiStatement(new VarExpression(l, null), phi);
						
						x.add(0, assign);
					}
				}
				
				insertion.put(x, i);
				if(process.get(x) < i) {
					process.put(x, i);
					queue.add(x);
				}
			}
		}
	}
	
	void insertPhis() {
		int i = 0;
		for(Local l : locals) {
			i++;
			
			LinkedList<BasicBlock> queue = new LinkedList<>();
			for(BasicBlock b : assigns.get(l)) {
				process.put(b, i);
				queue.add(b);
			}
			
			while(!queue.isEmpty()) {
				insertPhis(queue.poll(), l, i, queue);
			}
		}
	}
	
	VersionedLocal _gen_name(int index, boolean isStack) {
		LocalsHandler handler = graph.getLocals();
		Local l = handler.get(index, isStack);
		int subscript = counters.get(l);
		stacks.get(l).push(subscript);
		counters.put(l, subscript+1);
		return handler.get(index, subscript, isStack);
	}
	
	VersionedLocal _top(Statement root, int index, boolean isStack) {
		LocalsHandler handler = graph.getLocals();
		Local l = handler.get(index, isStack);
		Stack<Integer> stack = stacks.get(l);
		if(stack == null) {
			System.err.println(graph);
			System.err.println(stacks);
			throw new NullPointerException(root.toString() + ", " +  l.toString());
		} else if(stack.isEmpty()) {
			System.err.println(graph);
			System.err.println(stacks);
			throw new IllegalStateException(root.toString() + ", " +  l.toString());
		}
		int subscript = stack.peek();
		return handler.get(index, subscript, isStack);
	}
	
	void renamePhis(BasicBlock b) {
		for(Statement stmt : b) {
			if(stmt.getOpcode() == Opcode.PHI_STORE) {
				CopyPhiStatement copy = (CopyPhiStatement) stmt;
				VarExpression var = copy.getVariable();
				Local lhs = var.getLocal();
				VersionedLocal vl = _gen_name(lhs.getIndex(), lhs.isStack());
				var.setLocal(vl);;
				defs.put(vl, copy);
			}
		}
	}
	
	void renameNonPhis(BasicBlock b) {
		for(Statement stmt : b) {
			int opcode = stmt.getOpcode();
			if(opcode == Opcode.LOCAL_STORE) {
				CopyVarStatement copy = (CopyVarStatement) stmt;
				VarExpression var = copy.getVariable();
				Local lhs = var.getLocal();
				VersionedLocal vl = _gen_name(lhs.getIndex(), lhs.isStack());
				var.setLocal(vl);
				defs.put(vl, copy);
			}
			
			if(opcode != Opcode.PHI_STORE) {
				for(Statement s : stmt) {
					if(s.getOpcode() == Opcode.LOCAL_LOAD) {
						VarExpression var = (VarExpression) s;
						Local l = var.getLocal();
						var.setLocal(_top(s, l.getIndex(), l.isStack()));
					}
				}
			}
		}
	}
	
	void fixPhiArgs(BasicBlock b, BasicBlock succ) {
		for(Statement stmt : succ) {
			if(stmt.getOpcode() == Opcode.PHI_STORE) {
				CopyPhiStatement copy = (CopyPhiStatement) stmt;
				PhiExpression phi = copy.getExpression();
				Expression e = phi.getArgument(b);
				if(e.getOpcode() == Opcode.LOCAL_LOAD) {
					Local l = (VersionedLocal) ((VarExpression) e).getLocal();
					l = _top(stmt, l.getIndex(), l.isStack());
					try {
						AbstractCopyStatement varDef = defs.get(l);
						if(copy.getType() == null) {
							Type t = TypeUtils.asSimpleType(varDef.getType());
							copy.getVariable().setType(t);
							phi.setType(t);
						} else {
							Type t = varDef.getType();
							Type oldT = copy.getType();
							// TODO: common supertypes
							if(!oldT.equals(TypeUtils.asSimpleType(t))) {
								throw new IllegalStateException(l + " " + copy + " " + t + " " + copy.getType());
							}
						}
						VarExpression var = new VarExpression(l, varDef.getType());
						phi.setArgument(b, var);
					} catch (IllegalStateException eg) {
						System.err.println(graph);
						System.err.println(succ.getId() + ": " + phi.getId() + ". " + phi);
						throw eg;
					}
				} else {
					throw new UnsupportedOperationException(String.valueOf(e));
				}
			}
		}
	}
	
	void search(BasicBlock b, Set<BasicBlock> vis) {
		if(vis.contains(b)) {
			return;
		}
		vis.add(b);
		
		renamePhis(b);
		renameNonPhis(b);
		
		List<FlowEdge<BasicBlock>> succs = new ArrayList<>();
		for(FlowEdge<BasicBlock> succE : graph.getEdges(b)) {
			succs.add(succE);
		}
		
		Collections.sort(succs, new Comparator<FlowEdge<BasicBlock>>() {
			@Override
			public int compare(FlowEdge<BasicBlock> o1, FlowEdge<BasicBlock> o2) {
				return o1.dst.compareTo(o2.dst);
			}
		});
		
		for(FlowEdge<BasicBlock> succE : succs) {
			BasicBlock succ = succE.dst;
			fixPhiArgs(b, succ);
		}
		
		for(FlowEdge<BasicBlock> succE : succs) {
			BasicBlock succ = succE.dst;
			search(succ, vis);
		}
		
		for (Statement s : b) {
			if (s.getOpcode() == Opcode.PHI_STORE || s.getOpcode() == Opcode.LOCAL_STORE) {
				AbstractCopyStatement cvs = (AbstractCopyStatement) s;
				Local l = cvs.getVariable().getLocal();
				l = graph.getLocals().get(l.getIndex(), l.isStack());
				stacks.get(l).pop();
			}
		}
	}
	
	void rename() {
		for(Local l : locals) {
			counters.put(l, 0);
			stacks.put(l, new Stack<>());
		}
		
		Set<BasicBlock> vis = new HashSet<>();
		for(BasicBlock e : graph.getEntries()) {
			search(e, vis);
		}
	}

	void ssa() {
		liveness = new LivenessAnalyser(StatementGraphBuilder.create(graph));
		
		BasicBlock exit = new BasicBlock(graph, graph.size() * 2, null);
		for(BasicBlock b : graph.vertices()) {
			if(graph.getEdges(b).size() == 0) {
				graph.addEdge(b, new DummyEdge<>(b, exit));
			}
			
			insertion.put(b, 0);
			process.put(b, 0);
		}
		
		doms = new TarjanDominanceComputor<>(graph);
		insertPhis();
		rename();
		
		graph.removeVertex(exit);
	}
	
	ControlFlowGraphBuilder reduce() {
		while(mergeImmediates() > 0);
		findComponents();
		ssa();
		return this;
	}
	
	public static ControlFlowGraph build(MethodNode method) {
		ControlFlowGraphBuilder builder = new ControlFlowGraphBuilder(method);
		return builder.build().reduce().graph;	
	}
}