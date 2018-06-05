package org.mapleir.ir.cfg.builder;

import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.*;
import org.mapleir.ir.TypeUtils;
import org.mapleir.ir.TypeUtils.ArrayType;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.ExpressionStack;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.*;
import org.mapleir.ir.code.expr.ArithmeticExpr.Operator;
import org.mapleir.ir.code.expr.ComparisonExpr.ValueComparisonType;
import org.mapleir.ir.code.expr.invoke.DynamicInvocationExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import org.mapleir.ir.code.stmt.*;
import org.mapleir.ir.code.stmt.ConditionalJumpStmt.ComparisonType;
import org.mapleir.ir.code.stmt.MonitorStmt.MonitorMode;
import org.mapleir.ir.code.stmt.copy.CopyVarStmt;
import org.mapleir.ir.locals.Local;
import org.mapleir.stdlib.util.StringHelper;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

import java.util.*;
import java.util.Map.Entry;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.tree.AbstractInsnNode.*;

public class GenerationPass extends ControlFlowGraphBuilder.BuilderPass {
	
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

	private final GenerationVerifier verifier;
	
	protected final InsnList insns;
	private final BitSet finished;
	private final LinkedList<LabelNode> queue;
	private final Set<LabelNode> marks;
	private final Map<BasicBlock, ExpressionStack> inputStacks;
	
	private BitSet stacks;
	protected BasicBlock currentBlock;
	protected ExpressionStack currentStack;
	protected boolean saved;

	// describes the "natural order" of the blocks, useful at this stage because we're close to the bytecode level
	// especially necessary for building the exception ranges metadata from the bytecode handler tables.
	// previously this was accomplished using getNumericId() but it's better to not rely on that instead.
	private final HashMap<BasicBlock, Integer> orderMap;

	public GenerationPass(ControlFlowGraphBuilder builder) {
		super(builder);
		
		/* a block can exist in the map in the graph 
		 * but not be populated yet.
		 * we do this so that when a flow function is reached, 
		 * we can create the block reference and then handle
		 * the creation mechanism later. */
		finished = new BitSet();
		queue = new LinkedList<>();
		stacks = new BitSet();
		marks = new HashSet<>();
		inputStacks = new HashMap<>();
		orderMap = new HashMap<>();
		
		insns = builder.method.instructions;
		
		if(GenerationVerifier.VERIFY) {
			verifier = new GenerationVerifier(builder);
		} else {
			verifier = null;
		}
	}

	private boolean isContiguous(ExceptionRange<BasicBlock> exceptionRange) {
		ListIterator<BasicBlock> lit = exceptionRange.getNodes().listIterator();
		while(lit.hasNext()) {
			BasicBlock prev = lit.next();
			if(lit.hasNext()) {
				BasicBlock b = lit.next();
				if(orderMap.get(prev) >= orderMap.get(b)) {
					return false;
				}
			}
		}
		return true;
	}

	protected BasicBlock makeBlock(LabelNode label) {
		int id = ++builder.count;
		BasicBlock b = new BasicBlock(builder.graph, id, label);
		orderMap.put(b, id);
		queue(label);
		builder.graph.addVertex(b);
		return b;
	}
	
	protected BasicBlock resolveTarget(LabelNode label) {
		BasicBlock block = builder.graph.getBlock(label);
		if(block == null) {
			block = makeBlock(label);
		}
		return block;
	}
	
	protected void setInputStack(BasicBlock b, ExpressionStack s) {
		if(inputStacks.containsKey(b)) {
			throw new UnsupportedOperationException(b.getDisplayName() + " already has inputstack: " + inputStacks.get(b) + " vs. " + s);
		}
		
		inputStacks.put(b, s);
	}
	
	protected ExpressionStack getInputStackFor(BasicBlock b) {
		return inputStacks.get(b);
	}
	
	private void init() {
		entry(checkLabel());
		
		for(TryCatchBlockNode tc : builder.method.tryCatchBlocks) {
			handler(tc);
		}
	}

	private LabelNode checkLabel() {
		AbstractInsnNode first = insns.getFirst();
		if (first == null) {
			LabelNode nFirst = new LabelNode();
			insns.add(nFirst);
			first = nFirst;
		} else if (!(first instanceof LabelNode)) {
			LabelNode nFirst = new LabelNode();
			insns.insertBefore(first, nFirst);
			first = nFirst;
		}
		return (LabelNode) first;
	}
	
	protected void entry(LabelNode firstLabel) {
		LabelNode l = new LabelNode();
		int id = ++builder.count;
		BasicBlock entry = new BasicBlock(builder.graph, id, l);
		orderMap.put(entry, id);
		entry.setFlag(BasicBlock.FLAG_NO_MERGE, true);
		
		builder.graph.addVertex(entry);
		builder.graph.getEntries().add(entry);
		setInputStack(entry, new ExpressionStack(16));
		defineInputs(builder.method, entry);
		insns.insertBefore(firstLabel, l);
		
		BasicBlock b = makeBlock(firstLabel);
		setInputStack(b, new ExpressionStack(16));
		queue(firstLabel);
		
		builder.graph.addEdge(new ImmediateEdge<>(entry, b));
	}
	
	protected void handler(TryCatchBlockNode tc) {
		LabelNode label = tc.handler;
		BasicBlock handler = resolveTarget(label);
		marks.add(tc.start);
		marks.add(tc.end);
		if(getInputStackFor(handler) != null) {
//			System.err.println(handler.getInputStack());
//			System.err.println("Double handler: " + handler.getId() + " " + tc);
			return;
		}
		
		ExpressionStack stack = new ExpressionStack(16);
		setInputStack(handler, stack);
		
		CaughtExceptionExpr expr = new CaughtExceptionExpr(tc.type);
		Type type = expr.getType();
		VarExpr var = _var_expr(0, type, true);
		CopyVarStmt stmt = copy(var, expr, handler);
		handler.add(stmt);
		
		stack.push(load_stack(0, type));
		
		queue(label);
		
		stacks.set(orderMap.get(handler));
	}
	
	protected void defineInputs(MethodNode m, BasicBlock b) {
		Type[] args = Type.getArgumentTypes(m.desc);
		int index = 0;
		if((m.access & Opcodes.ACC_STATIC) == 0) {
			addEntry(index, Type.getType("L" + m.owner.name + ";"), b);
			index++;
		}
		for(int i=0; i < args.length; i++) {
			Type arg = args[i];
			addEntry(index, arg, b);
			index += arg.getSize();
		}
	}
	
	private void addEntry(int index, Type type, BasicBlock b) {
		VarExpr var = _var_expr(index, type, false);
		CopyVarStmt stmt = selfDefine(var);
		builder.assigns.getNonNull(var.getLocal()).add(b);
		b.add(stmt);
	}
	
	protected CopyVarStmt selfDefine(VarExpr var) {
		return new CopyVarStmt(var, var, true);
	}
	
	protected void queue(LabelNode label) {
		if(!queue.contains(label)) {
			queue.addLast(label);
		}
	}
	
	protected void preprocess(BasicBlock b) {
		ExpressionStack stack = getInputStackFor(b).copy();
		stacks.set(orderMap.get(b));
		
		currentBlock = b;
		currentStack = stack;
		saved = false;
	}
	
	protected void process(LabelNode label) {
		/* it may not be properly initialised yet, however. */
		BasicBlock block = builder.graph.getBlock(label);
		
		/* if it is, we don't need to process it. */
		if(block != null && finished.get(orderMap.get(block))) {
			return;
		} else if(block == null) {
			block = makeBlock(label);
		} else {
			// i.e. not finished.
		}
		
		preprocess(block);
		
		/* populate instructions. */
		int codeIndex = insns.indexOf(label);
		finished.set(orderMap.get(block));
		while(codeIndex < insns.size() - 1) {
			AbstractInsnNode ain = insns.get(++codeIndex);
			int type = ain.type();
			
			if(ain.opcode() != -1) {
				process(block, ain);
			}
			
			if(type == LABEL) {
				// split into new block
				BasicBlock immediate = resolveTarget((LabelNode) ain);
				builder.graph.addEdge(new ImmediateEdge<>(block, immediate));
				break;
			} else if(type == JUMP_INSN) {
				JumpInsnNode jin = (JumpInsnNode) ain;
				BasicBlock target = resolveTarget(jin.label);
				
				if(jin.opcode() == JSR) {
					throw new UnsupportedOperationException("jsr " + builder.method);
				} else if(jin.opcode() == GOTO) {
					builder.graph.addEdge(new UnconditionalJumpEdge<>(block, target));
				} else {
					builder.graph.addEdge(new ConditionalJumpEdge<>(block, target, jin.opcode()));
					int nextIndex = codeIndex + 1;
					AbstractInsnNode nextInsn = insns.get(nextIndex);
					if(!(nextInsn instanceof LabelNode)) {
						LabelNode newLabel = new LabelNode();
						insns.insert(ain, newLabel);
						nextInsn = newLabel;
					}
					
					// create immediate successor reference if it's not already done
					BasicBlock immediate = resolveTarget((LabelNode) nextInsn);
					builder.graph.addEdge(new ImmediateEdge<>(block, immediate));
				}
				break;
			} else if(type == LOOKUPSWITCH_INSN) {
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				
				for(int i=0; i < lsin.keys.size(); i++) {
					BasicBlock target = resolveTarget(lsin.labels.get(i));
					builder.graph.addEdge(new SwitchEdge<>(block, target, lsin.keys.get(i)));
				}
				
				BasicBlock dflt = resolveTarget(lsin.dflt);
				builder.graph.addEdge(new DefaultSwitchEdge<>(block, dflt));
				break;
			} else if(type == TABLESWITCH_INSN) {
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				for(int i=tsin.min; i <= tsin.max; i++) {
					BasicBlock target = resolveTarget(tsin.labels.get(i - tsin.min));
					builder.graph.addEdge(new SwitchEdge<>(block, target, i));
				}
				BasicBlock dflt = resolveTarget(tsin.dflt);
				builder.graph.addEdge(new DefaultSwitchEdge<>(block, dflt));
				break;
			} else if(isExitOpcode(ain.opcode())) {
				break;
			}
		}
		
		// TODO: check if it should have an immediate.
		BasicBlock im = block.getImmediate();
		if (im != null/* && !queue.contains(im)*/) {
			// System.out.println("Updating " + block.getId() + " -> " + im.getId());
			// System.out.println("  Pre: " + currentStack);
			update_target_stack(block, im, currentStack);
			// System.out.println("  Pos: " + currentStack);
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
	
	protected void process(BasicBlock b, AbstractInsnNode ain) {
		int opcode = ain.opcode();

		// System.out.println("Executing " + Printer.OPCODES[opcode]);
		// System.out.println(" PreStack: " + currentStack);
		
		List<GenerationVerifier.VerifierRule> possibleRules = null;
		
		if(GenerationVerifier.VERIFY) {
			verifier.newContext(currentStack, ain, b);
			possibleRules = verifier.find_verify_matches();
		}
		
		switch (opcode) {
			case -1: {
				if (ain instanceof LabelNode)
					throw new IllegalStateException("Block should not contain label.");
				break;
			}
			case BIPUSH:
				_const((byte)((IntInsnNode) ain).operand, Type.BYTE_TYPE);
				break;
			case SIPUSH:
				_const((short)((IntInsnNode) ain).operand, Type.SHORT_TYPE);
				break;
			case ACONST_NULL:
				_const(null, TypeUtils.OBJECT_TYPE);
				break;
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
				_const((byte) (opcode - ICONST_M1 - 1), Type.BYTE_TYPE);
				break;
			case LCONST_0:
			case LCONST_1:
				_const((long) (opcode - LCONST_0), Type.LONG_TYPE);
				break;
			case FCONST_0:
			case FCONST_1:
			case FCONST_2:
				_const((float) (opcode - FCONST_0), Type.FLOAT_TYPE);
				break;
			case DCONST_0:
			case DCONST_1:
				_const((double) (opcode - DCONST_0), Type.DOUBLE_TYPE);
				break;
			case LDC:
				Object cst = ((LdcInsnNode) ain).cst;
				if(cst instanceof Number) {
					_const(cst, ConstantExpr.computeType(cst));
				} else {
					_const(cst, TypeUtils.unboxType(cst));
				}
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
				save_stack(false);
				_new_array(
					new Expr[] { pop() }, 
					TypeUtils.getPrimitiveArrayType(((IntInsnNode) ain).operand)
				);
				break;
			}
			case ANEWARRAY: {
				save_stack(false);
				String typeName = ((TypeInsnNode) ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "[L" + typeName + ";";
				else
					typeName = '[' + typeName;
				_new_array(
					new Expr[] { pop() },
					Type.getType(typeName)
				);
				break;
			}
			case MULTIANEWARRAY: {
				save_stack(false);
				MultiANewArrayInsnNode in = (MultiANewArrayInsnNode) ain;
				Expr[] bounds = new Expr[in.dims];
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
				_return(Type.getReturnType(builder.method.desc));
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
				String typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[') // arrays aren't objects.
					typeName = "L" + typeName + ";";
				_cast(Type.getType(typeName));
				break;
			case INSTANCEOF:
				typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "L" + typeName + ";";
				_instanceof(Type.getType(typeName));
				break;
			case NEW:
				typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "L" + typeName + ";";
				_new(Type.getType(typeName));
				break;
				
			case INVOKEDYNAMIC:
				InvokeDynamicInsnNode dy = (InvokeDynamicInsnNode) ain;
				_dynamic_call(dy.bsm, dy.bsmArgs, dy.desc, dy.name);
				break;
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
				_jump_cmp(resolveTarget(((JumpInsnNode) ain).label), ComparisonType.getType(opcode));
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
		
		if(GenerationVerifier.VERIFY) {
			if(possibleRules != null) {
				verifier.confirm_rules(possibleRules);
			}
		}
	}
	
	protected void _nop() {

	}

	protected void _const(Object o, Type type) {
		Expr e = new ConstantExpr(o, type, true);
		// int index = currentStack.height();
		// Type type = assign_stack(index, e);
		// push(load_stack(index, type));
		push(e);
	}

	protected void _compare(ValueComparisonType ctype) {
		save_stack(false);
		Expr right = pop();
		Expr left = pop();
		push(new ComparisonExpr(left, right, ctype));
	}

	protected void _return(Type type) {
		if (type == Type.VOID_TYPE) {
			currentStack.assertHeights(EMPTY_STACK_HEIGHTS);
			addStmt(new ReturnStmt());
		} else {
			save_stack(false);
			if(type.getSize() == 2) {
				currentStack.assertHeights(DOUBLE_RETURN_HEIGHTS);
			} else {
				currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
			}
			addStmt(new ReturnStmt(type, pop()));
		}
	}

	protected void _throw() {
		save_stack(false);
		currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
		addStmt(new ThrowStmt(pop()));
	}

	protected void _monitor(MonitorMode mode) {
		save_stack(false);
		addStmt(new MonitorStmt(pop(), mode));
	}

	protected void _arithmetic(Operator op) {
		save_stack(false);
		Expr e = new ArithmeticExpr(pop(), pop(), op);
		int index = currentStack.height();
		Type type = assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _neg() {
		save_stack(false);
		push(new NegationExpr(pop()));
	}
	
	protected void _arraylength() {
		save_stack(false);
		push(new ArrayLengthExpr(pop()));
	}
	
	protected void _load_array(ArrayType type) {
		save_stack(false);
		// prestack: var1, var0 (height = 2)
		// poststack: var0
		// assignments: var0 = var0[var1]
//		int height = currentStack.height();
		Expr index = pop();
		Expr array = pop();
		push(new ArrayLoadExpr(array, index, type));
//		assign_stack(height - 2, new ArrayLoadExpr(array, index, type));
//		push(load_stack(height - 2, type.getType()));
	}
	
	protected void _store_array(ArrayType type) {
		save_stack(false);
		Expr value = pop();
		Expr index = pop();
		Expr array = pop();
		addStmt(new ArrayStoreStmt(array, index, value, type));
	}
	
	protected void _pop(int amt) {
		for(int i=0; i < amt; ) {
			Expr top = pop();
			addStmt(new PopStmt(top));
			i += top.getType().getSize();
		}
	}
	
	protected void _dup() {
		// prestack: var0 (height = 1)
		// poststack: var1, var0
		// assignments: var1 = var0(initial)
		currentStack.assertHeights(DUP_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);
		
		Expr var0 = pop();

		Type var1Type = assign_stack(baseHeight, var0); // var1 = var0
		push(load_stack(baseHeight - 1, var0.getType())); //  push var0
		push(load_stack(baseHeight, var1Type)); // push var1
	}

	protected void _dup_x1() {
		// prestack: var1, var0 (height = 2)
		// poststack: var2, var1, var0
		// assignments: var0 = var1(initial)
		// assignments: var1 = var0(initial)
		// assignments: var2 = var1(initial)
		currentStack.assertHeights(DUP_X1_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);

		Expr var1 = pop();
		Expr var0 = pop();

		Type var3Type = assign_stack(baseHeight + 1, var0); // var3 = var0

		Type var0Type = assign_stack(baseHeight - 2, var1); // var0 = var1(initial)
		Type var2Type = assign_stack(baseHeight + 0, var1.copy()); // var2 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 1, var3Type)); // var1 = var3 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
		push(load_stack(baseHeight + 0, var2Type)); // push var2
	}

	protected void _dup_x2() {
		int baseHeight = currentStack.height();
		save_stack(false);

		if(currentStack.peek(1).getType().getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var1, var0
			// assignments: var0 = var2(initial)
			// assignments: var1 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP_X2_64_HEIGHTS);

			Expr var2 = pop();
			Expr var0 = pop();

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

			Expr var2 = pop();
			Expr var1 = pop();
			Expr var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)
			Type var5Type = assign_stack(baseHeight + 2, var1); // var5 = var1(initial)

			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var2.copy()); // var3 = var2(initial)
			Type var1Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var4Type)); // var1 = var4 = var0(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 2, var5Type)); // var2 = var5 = var1(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight + 0, var3Type)); // push var3
		}
	}

	protected void _dup2() {
		int baseHeight = currentStack.height();
		save_stack(false);

		if(peek().getType().getSize() == 2) {
			// prestack: var0 (height = 2)
			// poststack: var2, var0
			// assignments: var2 = var0

			Expr var0 = pop();

			Type var2Type = assign_stack(baseHeight, var0); // var2 = var0
			push(load_stack(baseHeight - 2, var0.getType())); //  push var0
			push(load_stack(baseHeight, var2Type)); // push var2
		} else {
			// prestack: var1, var0 (height = 2)
			// poststack: var3, var2, var1, var0
			// assignments: var2 = var0(initial)
			// assignments: var3 = var1(initial)
			currentStack.assertHeights(DUP2_32_HEIGHTS);

			Expr var1 = pop();
			Expr var0 = pop();

			Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
			Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

			push(load_stack(baseHeight - 2, var0.getType())); // push var0
			push(load_stack(baseHeight - 1, var1.getType())); // push var1
			push(load_stack(baseHeight + 0, var2Type)); // push var2
			push(load_stack(baseHeight + 1, var3Type)); // push var3
		}
	}

	protected void _dup2_x1() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();
		save_stack(false);

		if(topType.getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var2, var0
			// assignments: var0 = var2(initial)
			// assignemnts: var2 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP2_X1_64_HEIGHTS);

			Expr var2 = pop();
			Expr var0 = pop();

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

			Expr var2 = pop();
			Expr var1 = pop();
			Expr var0 = pop();

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

	protected void _dup2_x2() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();
		save_stack(false);
		
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

				Expr var2 = pop();
				Expr var0 = pop();

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

				Expr var2 = pop();
				Expr var1 = pop();
				Expr var0 = pop();

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

				Expr var3 = pop();
				Expr var2 = pop();
				Expr var0 = pop();

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

				Expr var3 = pop();
				Expr var2 = pop();
				Expr var1 = pop();
				Expr var0 = pop();

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
	
	protected void _swap() {
		// prestack: var1, var0 (height = 2)
		// poststack: var1, var0
		// assignments: var0 = var1 (initial)
		// assignments: var1 = var0 (initial)

		currentStack.assertHeights(SWAP_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);

		Expr var1 = pop();
		Expr var0 = pop();

		Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
		Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

		Type var0Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var3Type)); // var0 = var3 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 0, var2Type)); // var1 = var2 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
	}
	
	protected void _cast(Type type) {
		save_stack(false);
		Expr e = new CastExpr(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _instanceof(Type type) {
		save_stack(false);
		InstanceofExpr e = new InstanceofExpr(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, Type.BOOLEAN_TYPE));
	}
	
	protected void _new(Type type) {
		save_stack(false);
		int index = currentStack.height();
		AllocObjectExpr e = new AllocObjectExpr(type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _new_array(Expr[] bounds, Type type) {
		int index = currentStack.height();
		NewArrayExpr e = new NewArrayExpr(bounds, type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _dynamic_call(Handle bsm, Object[] bsmArgs, String bootstrapDesc, String boundName) {
		save_stack(false);
		// these are the additional bound variables passed into the boostrap method (typically metafactory) 
		Expr[] boundArgs = new Expr[Type.getArgumentTypes(bootstrapDesc).length];
		for(int i = boundArgs.length - 1; i >= 0; i--) {
			boundArgs[i] = pop();
		}
		
		// copy the bsm and bsm args
		Handle bsmCopy = new Handle(bsm.getTag(), bsm.getOwner(), bsm.getName(), bsm.getDesc());
		Handle provider = new Handle(bsm.getTag(), bsm.getOwner(), bsm.getName(), bsm.getDesc());
		Object[] bsmArgsCopy = new Object[bsmArgs.length];
		System.arraycopy(bsmArgs, 0, bsmArgsCopy, 0, bsmArgsCopy.length);
		DynamicInvocationExpr expr = new DynamicInvocationExpr(bsmCopy, bsmArgsCopy, bootstrapDesc, boundArgs, boundName);
		
		if(expr.getType() == Type.VOID_TYPE) {
			addStmt(new PopStmt(expr));
		} else {
			int index = currentStack.height();
			Type type = assign_stack(index, expr);
			push(load_stack(index, type));
		}
		
		// TODO: redo vm lambdas as static resolution calls/concrete calls.
	}
	
	protected void _call(int op, String owner, String name, String desc) {
		save_stack(false);
		int argLen = Type.getArgumentTypes(desc).length + (op == INVOKESTATIC ? 0 : 1);
		Expr[] args = new Expr[argLen];
		for (int i = args.length - 1; i >= 0; i--) {
			args[i] = pop();
		}
		InvocationExpr callExpr;
		switch(op) {
			case Opcodes.INVOKEVIRTUAL:
			case Opcodes.INVOKEINTERFACE:
			case Opcodes.INVOKESPECIAL:
				callExpr = new VirtualInvocationExpr(VirtualInvocationExpr.resolveCallType(op), args, owner, name, desc);
				break;
			case Opcodes.INVOKESTATIC:
				callExpr = new StaticInvocationExpr(args, owner, name, desc);
				break;
			default:
				throw new IllegalArgumentException("invalid call opcode " + op);
		}
		if(callExpr.getType() == Type.VOID_TYPE) {
			addStmt(new PopStmt(callExpr));
		} else {
			int index = currentStack.height();
			Type type = assign_stack(index, callExpr);
			push(load_stack(index, type));
		}
	}
	
	protected void _switch(LinkedHashMap<Integer, BasicBlock> targets, BasicBlock dflt) {
		save_stack(false);
		Expr expr = pop();
		
		for (Entry<Integer, BasicBlock> e : targets.entrySet()) {
			update_target_stack(currentBlock, e.getValue(), currentStack);
		}
		
		update_target_stack(currentBlock, dflt, currentStack);
		
		addStmt(new SwitchStmt(expr, targets, dflt));
	}

	protected void _store_field(int opcode, String owner, String name, String desc) {
		save_stack(false);
		if(opcode == PUTFIELD) {
			Expr val = pop();
			Expr inst = pop();
			addStmt(new FieldStoreStmt(inst, val, owner, name, desc));
		} else if(opcode == PUTSTATIC) {
			Expr val = pop();
			addStmt(new FieldStoreStmt(null, val, owner, name, desc));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	protected void _load_field(int opcode, String owner, String name, String desc) {
		save_stack(false);
		if(opcode == GETFIELD || opcode == GETSTATIC) {
			Expr inst = null;
			if(opcode == GETFIELD) {
				inst = pop();
			}
			FieldLoadExpr fExpr = new FieldLoadExpr(inst, owner, name, desc);
			int index = currentStack.height();
			Type type = assign_stack(index, fExpr);
			push(load_stack(index, type));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	protected void _store(int index, Type type) {
		save_stack(false);
		Expr expr = pop();
		VarExpr var = _var_expr(index, expr.getType(), false);
		addStmt(copy(var, expr));
	}

	protected void _load(int index, Type type) {
		VarExpr e = _var_expr(index, type, false);
		// assign_stack(currentStack.height(), e);
		push(e);
	}

	protected void _inc(int index, int amt) {
		save_stack(false);
		VarExpr load = _var_expr(index, Type.INT_TYPE, false);
		ArithmeticExpr inc = new ArithmeticExpr(new ConstantExpr(/*allow rebox*/amt), load, Operator.ADD);
		VarExpr var = _var_expr(index, Type.INT_TYPE, false);
		addStmt(copy(var, inc));
	}
	
	protected CopyVarStmt copy(VarExpr v, Expr e) {
		return copy(v, e, currentBlock);
	}
	
	protected CopyVarStmt copy(VarExpr v, Expr e, BasicBlock b) {
		builder.assigns.getNonNull(v.getLocal()).add(b);
		return new CopyVarStmt(v.getParent() != null? v.copy() : v, e.getParent() != null? e.copy() : e);
	}
	
	protected VarExpr _var_expr(int index, Type type, boolean isStack) {
		Local l = builder.graph.getLocals().get(index, isStack);
		builder.locals.add(l);
		return new VarExpr(l, type);
	}
	
	// var[index] = expr
	protected Type assign_stack(int index, Expr expr) {
		if(expr.getOpcode() == Opcode.LOCAL_LOAD) {
			VarExpr v = (VarExpr) expr;
			if(v.getIndex() == index && v.getLocal().isStack()) {
				return expr.getType();
			}
		}
		Type type = expr.getType();
		VarExpr var = _var_expr(index, type, true);
		CopyVarStmt stmt = copy(var, expr);
		addStmt(stmt);
		return type;
	}
	
	protected Expr load_stack(int index, Type type) {
		return _var_expr(index, type, true);
	}
	
	protected void _jump_cmp(BasicBlock target, ComparisonType type, Expr left, Expr right) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new ConditionalJumpStmt(left, right, target, type));
	}
	
	protected void _jump_cmp(BasicBlock target, ComparisonType type) {
		save_stack(false);
		Expr right = pop();
		Expr left = pop();
		_jump_cmp(target, type, left, right);
	}
	
	protected void _jump_cmp0(BasicBlock target, ComparisonType type) {
		save_stack(false);
		Expr left = pop();
		ConstantExpr right = new ConstantExpr((byte)0, Type.BYTE_TYPE, false);
		_jump_cmp(target, type, left, right);
	}

	protected void _jump_null(BasicBlock target, boolean invert) {
		save_stack(false);
		Expr left = pop();
		ConstantExpr right = new ConstantExpr(null, TypeUtils.OBJECT_TYPE);
		ComparisonType type = invert ? ComparisonType.NE : ComparisonType.EQ;
		
		_jump_cmp(target, type, left, right);
	}

	protected void _jump_uncond(BasicBlock target) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new UnconditionalJumpStmt(target));
	}
	
	protected Expr _pop(Expr e) {
		if(e.getParent() != null) {
			return e.copy();
		} else {
			return e;
		}
	}
	
	protected Expr pop() {
		return _pop(currentStack.pop());
	}
	
	protected Expr peek() {
		return currentStack.peek();
	}

	protected void push(Expr e) {
		currentStack.push(e);
	}
	
	protected void addStmt(Stmt stmt) {
		if(GenerationVerifier.VERIFY) {
			verifier.addEvent(new GenerationVerifier.GenerationMessageEvent(" adding " + stmt));
		}
		currentBlock.add(stmt);
	}
	
	protected void save_stack() {
		save_stack(true);
	}
	
	protected void save_stack(boolean check) {
		// System.out.println("Saving " + currentBlock.getId());
		if (!currentBlock.isEmpty() && currentBlock.get(currentBlock.size() - 1).canChangeFlow()) {
			throw new IllegalStateException("Flow instruction already added to block; cannot save stack: "  + currentBlock.getDisplayName());
		}
		
		// System.out.println("\n   Befor: " + currentStack);
		// System.out.println("     With size: " + currentStack.size());
		// System.out.println("     With height: " + currentStack.height());
		
		ExpressionStack copy = currentStack.copy();
		int len = currentStack.size();
		currentStack.clear();
		
		int height = 0;
		for(int i=len-1; i >= 0; i--) {
			// peek(0) = top
			// peek(len-1) = btm

			int index = height;
			Expr expr = copy.peek(i);
			
			if(expr.getParent() != null) {
				expr = expr.copy();
			}
			
			// System.out.println("    Pop: " + expr + ":" + expr.getType());
			// System.out.println("    Idx: " + index);
			Type type = assign_stack(index, expr);
			Expr e = load_stack(index, type);
			// System.out.println("    Push " + e + ":" + e.getType());
			// System.out.println("    tlen: " + type.getSize());

			currentStack.push(e);
			
			height += type.getSize();
		}
		
		if(check) {
			saved = true;
		}
		
		//System.out.println("   After: " + currentStack + "\n");
	}

	protected boolean can_succeed(ExpressionStack s, ExpressionStack succ) {
		// quick check stack heights
		if (s.height() != succ.height()) {
			return false;
		}
		ExpressionStack c0 = s.copy();
		ExpressionStack c1 = succ.copy();
		while (c0.height() > 0) {
			Expr e1 = c0.pop();
			Expr e2 = c1.pop();
			if (!(e1.getOpcode() == Opcode.LOCAL_LOAD) || !(e2.getOpcode() == Opcode.LOCAL_LOAD)) {
				return false;
			}
			if (((VarExpr) e1).getIndex() != ((VarExpr) e2).getIndex()) {
				return false;
			}
			if (e1.getType().getSize() != e2.getType().getSize()) {
				return false;
			}
		}
		return true;
	}
	
	private void update_target_stack(BasicBlock b, BasicBlock target, ExpressionStack stack) {
		if(stacks.get(orderMap.get(b)) && !saved) {
			save_stack();
		}
		// called just before a jump to a successor block may
		// happen. any operations, such as comparisons, that
		// happen before the jump are expected to have already
		// popped the left and right arguments from the stack before
		// checking the merge state.
		if (!stacks.get(orderMap.get(target))) {
			// unfinalised block found.
			// System.out.println("Setting target stack of " + target.getId() + " to " + stack);
			setInputStack(target, stack.copy());
			stacks.set(orderMap.get(target));

			queue(target.getLabelNode());
		} else if (!can_succeed(getInputStackFor(target), stack)) {
			// if the targets input stack is finalised and
			// the new stack cannot merge into it, then there
			// is an error in the bytecode (verifier error).
			
			// BasicDotConfiguration<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
			// DotWriter<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, builder.graph);
			// writer.removeAll().add(new ControlFlowGraphDecorator().setFlags(ControlFlowGraphDecorator.OPT_DEEP)).setName("6996").export();
			
			System.err.println("Current: " + stack + " in " + b.getDisplayName());
			System.err.println("Target : " + getInputStackFor(target) + " in " + target.getDisplayName());
			System.err.println(builder.graph);
			throw new IllegalStateException("Stack coherency mismatch into #" + target.getDisplayName());
		}
	}
	
	private List<BasicBlock> range(List<BasicBlock> gblocks, int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("start > end: " + start + " > " + end);
		}
		BasicBlock startBlock = null, endBlock = null;
		int startIndex = 0, endIndex = 0;
//		String startName = StringHelper.createBlockName(start);
//		String endName = StringHelper.createBlockName(end);
		int blockIndex = 0;
		for(BasicBlock b : gblocks) {
			if(orderMap.get(b) == start) {
				startBlock = b;
				startIndex = blockIndex;
			}
			if(orderMap.get(b) == end) {
				endBlock = b;
				endIndex = blockIndex;
			}
			
			if(startBlock != null && endBlock != null) {
				break;
			}
			blockIndex++;
		}
		
		if(startBlock == null || endBlock == null) {
			throw new UnsupportedOperationException("start or end null, " + start + " " + end);
		} else if(startIndex > endIndex) {
			throw new IllegalArgumentException("startIndex > endIndex: " + startIndex + " > " + endIndex);
		}

		List<BasicBlock> blocks = new ArrayList<>();
		for(int i=startIndex; i <= endIndex; i++) {
			BasicBlock block = gblocks.get(i);
			if(block == null) {
				throw new IllegalArgumentException("block " + StringHelper.createBlockName(i) + "not in range");
			}
			blocks.add(block);
		}
		
		return blocks;
	}
	
	private void makeRanges(List<BasicBlock> order) {
//		System.out.println(builder.graph);
//		BasicDotConfiguration<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
//		DotWriter<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, builder.graph);
//		writer.removeAll().add(new ControlFlowGraphDecorator().setFlags(ControlFlowGraphDecorator.OPT_DEEP)).setName("test9999").export();
		
		Map<String, ExceptionRange<BasicBlock>> ranges = new HashMap<>();
		for(TryCatchBlockNode tc : builder.method.tryCatchBlocks) {
			
//			System.out.printf("from %d to %d, handler:%d, type:%s.%n", insns.indexOf(tc.start), insns.indexOf(tc.end), insns.indexOf(tc.handler), tc.type);
//			System.out.println(String.format("%s:%s:%s", BasicBlock.createBlockName(insns.indexOf(tc.start)), BasicBlock.createBlockName(insns.indexOf(tc.end)), builder.graph.getBlock(tc.handler).getId()));
			
			int start = orderMap.get(builder.graph.getBlock(tc.start));
			int end = orderMap.get(builder.graph.getBlock(tc.end)) - 1;
			
			List<BasicBlock> range = range(order, start, end);
			BasicBlock handler = builder.graph.getBlock(tc.handler);
			String key = String.format("%d:%d:%s", start, end, orderMap.get(handler));
			
			ExceptionRange<BasicBlock> erange;
			if(ranges.containsKey(key)) {
				erange = ranges.get(key);
			} else {
				erange = new ExceptionRange<>();
				erange.setHandler(handler);
				erange.addVertices(range);
				ranges.put(key, erange);
				
				if(!isContiguous(erange)) {
					System.out.println(erange + " not contiguous");
				}
				builder.graph.addRange(erange);
			}
			
			/* add L; so that the internal descriptor here matches the
			 * one provided by Type.getType(Class) and therefore
			 * ExceptionAnalysis.getType(Class). 
			 * 
			 * 26/08/17: changed Type.getType to return consistent
			 * object refs and to not create Types of sort METHOD
			 * if the given descriptor isn't intended for getType
			 * (getObjectType instead), shouldn't have this problem now.*/
			erange.addType(tc.type != null ? Type.getType("L" + tc.type + ";") : TypeUtils.THROWABLE);
			
			ListIterator<BasicBlock> lit = range.listIterator();
			while(lit.hasNext()) {
				BasicBlock block = lit.next();
				builder.graph.addEdge(new TryCatchEdge<>(block, erange));
			}
		}
	}
	
	private void ensureMarks() {
		// it is possible for the start/end blocks of ranges
		// to not be generated/blocked during generation,
		// so we generate them here.
		
		for(LabelNode m : marks) {
			// creates the block if it's not
			// already in the graph.
			resolveTarget(m);
		}
		// queue is irrelevant at this point.
		queue.clear();
		
		// since the blocks created were not reached
		// it means that their inputstacks were empty.
		// this also means no edges are needed to connect
		// them except for the range edges which are done
		// later.
		// we can also rely on the natural label ordering
		// code to fix up the graph to make it look like
		// this block is next to the previous block in code.
	}
	
	private void processQueue() {
		while(!queue.isEmpty()) {
			LabelNode label = queue.removeFirst();
			process(label);
		}
		
		ensureMarks();
		
		List<BasicBlock> blocks = new ArrayList<>(builder.graph.vertices());
		blocks.sort((o1, o2) -> {
			int i1 = insns.indexOf(o1.getLabelNode());
			int i2 = insns.indexOf(o2.getLabelNode());
			return Integer.compare(i1, i2);
		});
		builder.graph.relabel(blocks);
		orderMap.clear(); // since we're changing the "natural" order of the blocks, we need to rebuild the order map.
		for (int i = 0; i < blocks.size(); i++)
			orderMap.put(blocks.get(i), i);
		makeRanges(blocks);
	}

	@Override
	public void run() {
		if(builder.count == 0) { // no blocks created
			init();
			processQueue();
		}
	}
}
