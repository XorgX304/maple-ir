package org.mapleir.deob.interproc.exp2;

import org.mapleir.deob.interproc.exp2.context.CallingContext;
import org.mapleir.ir.cfg.edge.FlowEdge;

public class CallEdge extends FlowEdge<CallGraphBlock> {
	
	public static final int TYPE_ID = 8;
	
	private final CallingContext context;
	
	public CallEdge(CallGraphBlock src, CallGraphBlock dst, CallingContext context) {
		super(TYPE_ID, src, dst);
		
		this.context = context;
	}

	@Override
	public String toGraphString() {
		return String.format("Call #%s -> #%s", src.getId(), dst.getId());
	}

	@Override
	public String toString() {
		return String.format("Call %s -> %s", src.toString(), dst.toString());
	}

	@Override
	public String toInverseString() {
		return String.format("Call #%s <- #%s", dst.getId(), src.getId());
	}

	@Override
	public FlowEdge<CallGraphBlock> clone(CallGraphBlock src, CallGraphBlock dst) {
		return new CallEdge(src, dst, context);
	}

	public CallingContext getContext() {
		return context;
	}
}