package org.mapleir.ir.cfg.builder;

import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.FlowEdge;
import org.mapleir.flowgraph.edges.FlowEdges;
import org.mapleir.flowgraph.edges.TryCatchEdge;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.locals.Local;
import org.mapleir.stdlib.collections.graph.algorithms.SimpleDfs;

import java.util.*;
import java.util.Map.Entry;

public class NaturalisationPass extends ControlFlowGraphBuilder.BuilderPass {

	public NaturalisationPass(ControlFlowGraphBuilder builder) {
		super(builder);
	}

	@Override
	public void run() {
		mergeImmediates();
	}
	
	int mergeImmediates() {
		class MergePair {
			final BasicBlock src;
			final BasicBlock dst;
			MergePair(BasicBlock src, BasicBlock dst)  {
				this.src = src;
				this.dst = dst;
			}
		}
		
		List<MergePair> merges = new ArrayList<>();
		Map<BasicBlock, BasicBlock> remap = new HashMap<>();
		Map<BasicBlock, List<ExceptionRange<BasicBlock>>> ranges = new HashMap<>();

		for(BasicBlock b : SimpleDfs.topoorder(builder.graph, builder.head)) {
			BasicBlock in = b.cfg.getIncomingImmediate(b);
			if(in == null) {
				continue;
			}
			if(in.isFlagSet(BasicBlock.FLAG_NO_MERGE)) {
				continue;
			}
			Set<FlowEdge<BasicBlock>> inSuccs = in.cfg.getSuccessors(e -> !(e instanceof TryCatchEdge), in);
			if(inSuccs.size() != 1 || builder.graph.getReverseEdges(b).size() != 1) {
				continue;
			}
			
			List<ExceptionRange<BasicBlock>> range1 = b.cfg.getProtectingRanges(b);
			List<ExceptionRange<BasicBlock>> range2 = in.cfg.getProtectingRanges(in);
			
			if(!range1.equals(range2)) {
				continue;
			}
			
			ranges.put(b, range1);
			ranges.put(in, range2);
			
			merges.add(new MergePair(in, b));
			
			remap.put(in, in);
			remap.put(b, b);
		}
		
		for(MergePair p : merges) {
			BasicBlock src = remap.get(p.src);
			BasicBlock dst = p.dst;
			
			dst.transfer(src);
			
			for(FlowEdge<BasicBlock> e : builder.graph.getEdges(dst)) {
				// since the ranges are the same, we don't need
				// to clone these.
				if(e.getType() != FlowEdges.TRYCATCH) {
					BasicBlock edst = e.dst();
					edst = remap.getOrDefault(edst, edst);
					builder.graph.addEdge(e.clone(src, edst));
				}
			}
			builder.graph.removeVertex(dst);
			
			remap.put(dst, src);
			
			for(ExceptionRange<BasicBlock> r : ranges.get(src)) {
				r.removeVertex(dst);
			}
			for(ExceptionRange<BasicBlock> r : ranges.get(dst)) {
				r.removeVertex(dst);
			}
			
			// System.out.printf("Merged %s into %s.%n", dst.getDisplayName(), src.getDisplayName());
		}
		
		// we need to update the assigns map if we change the cfg.
		for(Entry<Local, Set<BasicBlock>> e : builder.assigns.entrySet()) {
			Set<BasicBlock> set = e.getValue();
			Set<BasicBlock> copy = new HashSet<>(set);
			for(BasicBlock b : copy) {
				BasicBlock r = remap.getOrDefault(b, b);
				if(r != b) {
					set.remove(b);
					set.add(r);
				}
			}
		}
		
		return merges.size();
	}
}