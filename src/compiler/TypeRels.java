package compiler;

import compiler.AST.*;
import compiler.lib.*;

import java.sql.Ref;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TypeRels {

	public static Map<String, String> superType = new HashMap<>();

	public static boolean isSubtype(TypeNode a, TypeNode b) {
		return a.getClass().equals(b.getClass()) ||
				((a instanceof BoolTypeNode) && (b instanceof IntTypeNode)) ||
				((a instanceof EmptyTypeNode) && (b instanceof RefTypeNode)) ||
				isSubClass(a, b) ||
				isOverride(a, b);
	}

	public static boolean isOverride(TypeNode a, TypeNode b) {
		if(!(a instanceof ArrowTypeNode) || !(b instanceof ArrowTypeNode))
			return false;

		ArrowTypeNode ar_a = (ArrowTypeNode) a;
		ArrowTypeNode ar_b = (ArrowTypeNode) b;
		boolean covariancy = true;

		for(int i = 0; i < ar_b.parlist.size(); i++) {
			TypeNode parA = ar_a.parlist.get(i);
			TypeNode parB = ar_b.parlist.get(i);
			if(!isSubtype(parB, parA))
				covariancy = false;
		}

		return isSubtype(ar_a.ret, ar_b.ret) && covariancy;

	}

	public static boolean isSubClass(TypeNode a, TypeNode b) {
		if(!(a instanceof RefTypeNode) || !(b instanceof RefTypeNode))
			return false;

		String a_id = ((RefTypeNode) a).classId;
		String b_id = ((RefTypeNode) b).classId;
		while(!a_id.equals(b_id)) {
			a_id = superType.get(a_id);
			if(Objects.equals(a_id, "")) return false;
		}
		return true;
	}

	public static TypeNode lowestCommonAncestor(TypeNode a, TypeNode b) {
		if(a instanceof RefTypeNode && b instanceof EmptyTypeNode) return a;
		if(a instanceof EmptyTypeNode && b instanceof RefTypeNode) return b;
		if(a instanceof RefTypeNode currentType && b instanceof RefTypeNode) {
			if(isSubtype(b, a)) return a;
			while(superType.containsKey(currentType.classId)) {
				currentType = new RefTypeNode(superType.get(currentType.classId));
				if(isSubtype(b, currentType)) return currentType;
			}
			return null;
		}

		if((a instanceof BoolTypeNode || a instanceof IntTypeNode) &&
				(b instanceof BoolTypeNode || b instanceof IntTypeNode)) {
			if(a instanceof IntTypeNode || b instanceof IntTypeNode) return new IntTypeNode();
			return new BoolTypeNode();
		}

		return null;
	}

}
