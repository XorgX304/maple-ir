package diamondlookup.maple;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.Sets;
import diamondlookup.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mapleir.SimpleInvocationResolver;
import org.mapleir.app.service.ApplicationClassSource;
import org.mapleir.app.service.InstalledRuntimeClassSource;
import org.mapleir.app.service.InvocationResolver;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static junit.framework.Assert.assertEquals;

public class InvocationResolverTest {

	ApplicationClassSource app;
	InvocationResolver resolver;
	
	@Before
	public void setUp() throws Exception {
		Collection<ClassNode> classes = new HashSet<>();
		/* load the app code */
		for (Class<?> c : new Class<?>[] { ISpeak.class, ISpeak2.class, EmptySpeakImpl.class, EmptySpeakImplChild.class,
				DiamondLookupTest.class }) {
			
			ClassReader cr = new ClassReader(name(c));
			ClassNode cn = new ClassNode();
			cr.accept(cn, 0);
			
			classes.add(cn);
		}
		
		app = new ApplicationClassSource("diamond-lookup-testapp", classes);
		app.addLibraries(new InstalledRuntimeClassSource(app));
		app.getClassTree();
		
		resolver = new SimpleInvocationResolver(app);
	}

	@Test
	public void testResolveVirtualCalls() {
		ClassNode cn = app.findClassNode(name(DiamondLookupTest.class));
		
		for(MethodNode m : cn.methods) {
			if(m.name.equals("test")) {
				ControlFlowGraph cfg = ControlFlowGraphBuilder.build(m);
				for(Stmt stmt : cfg.stmts()) {
					for(Expr e : stmt.enumerateOnlyChildren()) {
						if(e.getOpcode() == Opcode.INVOKE) {
							InvocationExpr ie = (InvocationExpr) e;
							
							if(ie.getOwner().equals("org/junit/Assert") && ie.getName().equals("assertEquals") && ie.getDesc().equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) {
								InvocationExpr arg1 = (InvocationExpr) ie.getArgumentExprs()[0];
								String arg2 = (String) ((ConstantExpr) ie.getArgumentExprs()[1]).getConstant();

								Class correctResolution;
								switch(arg1.getOwner()) {
									case "diamondlookup/EmptySpeakImpl":
									case "diamondlookup/EmptySpeakImplChild":
										correctResolution = ISpeak2.class;
										break;
									case "diamondlookup/EmptySpeakImplChild2":
										correctResolution = ISpeak3.class;
										break;
									default:
											throw new IllegalArgumentException();
								}
								Set<MethodNode> resolutionTargets = Sets.newHashSet(app.findClassNode(name(correctResolution)).getMethod("speak", "()Ljava/lang/String;", false));
								assertEquals(arg1.resolveTargets(resolver), resolutionTargets);
							}
						}
					}
				}
			}
		}
	}

	@After
	public void tearDown() throws Exception {
		app = null;
		resolver = null;
	}
	
	private static String name(Class<?> c) {
		return c.getName().replace(".", "/");
	}
}
