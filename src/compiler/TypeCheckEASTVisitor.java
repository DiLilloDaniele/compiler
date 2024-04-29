package compiler;

import compiler.AST.*;
import compiler.exc.*;
import compiler.lib.*;

import java.util.Objects;

import static compiler.TypeRels.*;

//visitNode(n) fa il type checking di un Node n e ritorna:
//- per una espressione, il suo tipo (oggetto BoolTypeNode o IntTypeNode)
//- per una dichiarazione, "null"; controlla la correttezza interna della dichiarazione
//(- per un tipo: "null"; controlla che il tipo non sia incompleto)
//
//visitSTentry(s) ritorna, per una STentry s, il tipo contenuto al suo interno
public class TypeCheckEASTVisitor extends BaseEASTVisitor<TypeNode,TypeException> {

	TypeCheckEASTVisitor() { super(true); } // enables incomplete tree exceptions
	TypeCheckEASTVisitor(boolean debug) { super(true,debug); } // enables print for debugging

	//checks that a type object is visitable (not incomplete) 
	private TypeNode ckvisit(TypeNode t) throws TypeException {
		visit(t);
		return t;
	} 

	@Override
	public TypeNode visitNode(ProgLetInNode n) throws TypeException {
		if (print) printNode(n);
		// visit all class declarations
		for (Node cl : n.classlist)
			try {
				visit(cl);
			} catch (IncomplException e) {
			}
		// visit all declarations
		for (Node dec : n.declist)
			try {
				visit(dec);
			} catch (IncomplException e) { 
			} catch (TypeException e) {
				System.out.println("Type checking error in a declaration: " + e.text);
			}
		return visit(n.exp);
	}

	@Override
	public TypeNode visitNode(ProgNode n) throws TypeException {
		if (print) printNode(n);
		return visit(n.exp);
	}



	@Override
	public TypeNode visitNode(FunNode n) throws TypeException {
		if (print) printNode(n,n.id);
		for (Node dec : n.declist)
			try {
				visit(dec);
			} catch (IncomplException e) { 
			} catch (TypeException e) {
				System.out.println("Type checking error in a declaration: " + e.text);
			}
		if ( !isSubtype(visit(n.exp),ckvisit(n.retType)) ) 
			throw new TypeException("Wrong return type for function " + n.id,n.getLine());
		return null;
	}

	@Override
	public TypeNode visitNode(VarNode n) throws TypeException {
		if (print) printNode(n,n.id);

		TypeNode t1 =  ckvisit(n.getType());
		TypeNode t2 = visit(n.exp);

		// check that both t1 and t2 are instance of a reference type
		if(t1 instanceof RefTypeNode && t2 instanceof RefTypeNode) {
			// check that both refers to the same class
			if ( !isSubtype((RefTypeNode)t2,(RefTypeNode) t1) && !isSubClass((RefTypeNode)t2,(RefTypeNode) t1))
				throw new TypeException("Incompatible class for variable " + n.id,n.getLine());
		}

		// otherwise check that between them exist a subtype relation
		if ( !isSubtype(t2,ckvisit(n.getType())) )
			throw new TypeException("Incompatible value for variable " + n.id,n.getLine());
		return null;
	}

	@Override
	public TypeNode visitNode(MethodNode n) throws TypeException {
		if (print) printNode(n,n.id);
		for (Node dec : n.declist)
			try {
				// type check for each declaration
				visit(dec);
			} catch (IncomplException e) {
			} catch (TypeException e) {
				System.out.println("Type checking error in a declaration: " + e.text);
			}
		// check that return type is compatible
		if ( !isSubtype(visit(n.exp),ckvisit(n.retType)) )
			throw new TypeException("Wrong return type for function " + n.id,n.getLine());
		return null;
	}

	@Override
	public TypeNode visitNode(ClassNode n) throws TypeException {
		if (print) printNode(n,n.id);
		boolean extension = false;
		// if this class extends another, add the supertype to the collection
		if(!Objects.equals(n.superId, "")) {
			superType.put(n.id, n.superId);
			extension = true;
		}
		// check if override is correctly done
		if(extension) {
			ClassTypeNode parent = (ClassTypeNode) n.superEntry.type;
			ClassTypeNode currentClass = n.type;
			for(int i = 0; i < n.fieldlist.size(); i++) {
				var field = n.fieldlist.get(i);
				/**
				 *  calcolo offset per recuperare il corrispettivo TypeNode presente
				 * in relativo ClassTypeNode
				 **/
				int position = -n.fieldlist.get(i).offset - 1;

				if(position < parent.allFields.size() && i < n.type.allFields.size()) {
					// overriding

					if(field.isOverride) {
						var fieldType = currentClass.allFields.get(position);
						var parentField = parent.allFields.get(position);
						if(!isSubtype(fieldType, parentField)) {
							throw new TypeException("Invalid override: Field " + i + "-th is not subtype of its parent field: ", n.getLine());
						}
					}
				}
			}

			for(int c = 0; c < n.methodlist.size(); c++) {
				int position = n.methodlist.get(c).offset;

				if(position < parent.allMethods.size()) {
					// overriding
					MethodTypeNode atn = n.type.allMethods.get(position);
					MethodTypeNode fatherAtn = parent.allMethods.get(position);
					if( !isSubtype(atn, fatherAtn) ) {
						throw new TypeException("Invalid overriding of " + c + "-th method in class " + n.id, n.getLine());
					}
				} else {
					// without overriding visit the method
					visit(n.methodlist.get(c));
				}
			}
		} else {
			for (Node method : n.methodlist)
				try {
					// otherwise visit all class methods without override checks
					visit(method);
				} catch (IncomplException e) {
				} catch (TypeException e) {
					throw new TypeException("Type checking error in a declaration of a method: " + e.text, n.getLine());
				}
		}

		return null;

	}

	@Override
	public TypeNode visitNode(PrintNode n) throws TypeException {
		if (print) printNode(n);
		return visit(n.exp);
	}

	@Override
	public TypeNode visitNode(IfNode n) throws TypeException {
		if (print) printNode(n);
		// check the boolean condition
		if ( !(isSubtype(visit(n.cond), new BoolTypeNode())) )
			throw new TypeException("Non boolean condition in if",n.getLine());
		TypeNode t = visit(n.th);
		TypeNode e = visit(n.el);
		// check the subtype relation between else and then returns
		if (isSubtype(t, e)) return e;
		if (isSubtype(e, t)) return t;
		//check if exist a common type or in subtype relation down to the extends chain for the if-then-else final type
		TypeNode lowestCommonAncestor = lowestCommonAncestor(t, e);
		if(lowestCommonAncestor != null) {
			return lowestCommonAncestor;
		}
		throw new TypeException("Incompatible types in then-else branches",n.getLine());
	}

	@Override
	public TypeNode visitNode(EqualNode n) throws TypeException {
		if (print) printNode(n);
		TypeNode l = visit(n.left);
		TypeNode r = visit(n.right);
		// subtype relation between two operands
		if ( !(isSubtype(l, r) || isSubtype(r, l)) )
			throw new TypeException("Incompatible types in equal",n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(TimesNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())
				&& isSubtype(visit(n.right), new IntTypeNode())) )
			throw new TypeException("Non integers in multiplication",n.getLine());
		return new IntTypeNode();
	}

	@Override
	public TypeNode visitNode(PlusNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())
				&& isSubtype(visit(n.right), new IntTypeNode())) )
			throw new TypeException("Non integers in sum",n.getLine());
		return new IntTypeNode();
	}

	@Override
	public TypeNode visitNode(NotNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between bool type and two operands
		if ( !(isSubtype(visit(n.node), new BoolTypeNode())))
			throw new TypeException("Non boolean in not",n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(CallNode n) throws TypeException {
		if (print) printNode(n,n.id);
		TypeNode t = visit(n.entry);
		ArrowTypeNode at;
		// check that it refers to a function or a class method
		if ( t instanceof ArrowTypeNode )
			at = (ArrowTypeNode) t;
		else if( t instanceof MethodTypeNode)
			at = ((MethodTypeNode)t).fun;
		else
			throw new TypeException("Invocation of a non-function "+n.id,n.getLine());

		// number and type of parameters check
		if ( !(at.parlist.size() == n.arglist.size()) )
			throw new TypeException("Wrong number of parameters in the invocation of "+n.id,n.getLine());
		for (int i = 0; i < n.arglist.size(); i++)
			if ( !(isSubtype(visit(n.arglist.get(i)),at.parlist.get(i))) )
				throw new TypeException("Wrong type for "+(i+1)+"-th parameter in the invocation of "+n.id,n.getLine());
		return at.ret;
	}

	@Override
	public TypeNode visitNode(LessEqualNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())))
			throw new TypeException("Non integers in lesseq, type is " + visit(n.left),n.getLine());
		if ( !(isSubtype(visit(n.right), new IntTypeNode())))
			throw new TypeException("Non integers in lesseq, type is " + visit(n.right),n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(GreaterEqualNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())))
			throw new TypeException("Non integers in greq",n.getLine());
		if ( !(isSubtype(visit(n.right), new IntTypeNode())))
			throw new TypeException("Non integers in greq",n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(OrNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between boolean type and two operands
		if ( !(isSubtype(visit(n.left), new BoolTypeNode())))
			throw new TypeException("Non boolean in or",n.getLine());
		if ( !(isSubtype(visit(n.right), new BoolTypeNode())))
			throw new TypeException("Non boolean in or",n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(AndNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between boolean type and two operands
		if ( !(isSubtype(visit(n.left), new BoolTypeNode())))
			throw new TypeException("Non boolean in and",n.getLine());
		if ( !(isSubtype(visit(n.right), new BoolTypeNode())))
			throw new TypeException("Non boolean in and",n.getLine());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(DivNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())
				&& isSubtype(visit(n.right), new IntTypeNode())) )
			throw new TypeException("Non integers in div",n.getLine());
		return new IntTypeNode();
	}

	@Override
	public TypeNode visitNode(MinusNode n) throws TypeException {
		if (print) printNode(n);
		// subtype relation between int type and two operands
		if ( !(isSubtype(visit(n.left), new IntTypeNode())
				&& isSubtype(visit(n.right), new IntTypeNode())) )
			throw new TypeException("Non integers in minus",n.getLine());
		return new IntTypeNode();
	}

	@Override
	public TypeNode visitNode(ClassCallNode n) throws TypeException {
		if (print) printNode(n,n.id);
		TypeNode obj = visit(n.entry);
		TypeNode t = visit(n.methodEntry);
		MethodTypeNode at;

		if(! (obj instanceof RefTypeNode))
			throw new TypeException("Invocation of a method of a non-reference "+n.idMethod,n.getLine());

		// check that the call refers to a method
		if(t instanceof MethodTypeNode)
			at = ((MethodTypeNode) t);
		else
			throw new TypeException("Invocation of a non-method "+n.idMethod,n.getLine());

		// controllo il numero e il tipo di parametri
		if ( !(at.fun.parlist.size() == n.arglist.size()) )
			throw new TypeException("Wrong number of parameters in the invocation of "+n.id,n.getLine());
		for (int i = 0; i < n.arglist.size(); i++)
			if ( !(isSubtype(visit(n.arglist.get(i)),at.fun.parlist.get(i))) )
				throw new TypeException("Wrong type for "+(i+1)+"-th parameter in the invocation of "+n.id,n.getLine());
		return at.fun.ret;
	}

	@Override
	public TypeNode visitNode(EmptyNode nullNode) throws TypeException {
		return new EmptyTypeNode();
	}

	@Override
	public TypeNode visitNode(NewNode n) throws TypeException {
		if (print) printNode(n,n.id);
		TypeNode t = visit(n.entry);
		ClassTypeNode at;

		// check that is a class to be instantiated
		if(t instanceof ClassTypeNode)
			at = (ClassTypeNode) t;
		else
			throw new TypeException("Invocation of a non-class ID "+n.id,n.getLine());

		// check the number of fields and type of field of the new instance call (referred to the class to instantiate)
		if ( !(at.allFields.size() == n.arglist.size()) )
			throw new TypeException("Wrong number of parameters in the invocation of "+n.id,n.getLine());
		for (int i = 0; i < n.arglist.size(); i++)
			if ( !(isSubtype(visit(n.arglist.get(i)),at.allFields.get(i))) )
				throw new TypeException("Wrong type for "+(i+1)+"-th parameter in the invocation of "+n.id,n.getLine());

		return new RefTypeNode(n.id);
	}

	@Override
	public TypeNode visitNode(IdNode n) throws TypeException {
		if (print) printNode(n,n.id);
		TypeNode t = visit(n.entry);
		// check the id refers to a function, method or class
		if (t instanceof ArrowTypeNode || t instanceof MethodTypeNode || t instanceof ClassTypeNode)
			throw new TypeException("Wrong usage of function identifier " + n.id,n.getLine());

		return t;
	}

	@Override
	public TypeNode visitNode(BoolNode n) {
		if (print) printNode(n,n.val.toString());
		return new BoolTypeNode();
	}

	@Override
	public TypeNode visitNode(IntNode n) {
		if (print) printNode(n,n.val.toString());
		return new IntTypeNode();
	}
	
	@Override
	public TypeNode visitNode(ArrowTypeNode n) throws TypeException {
		if (print) printNode(n);
		for (Node par: n.parlist) visit(par);
		visit(n.ret,"->"); //marks return type
		return null;
	}

	@Override
	public TypeNode visitNode(BoolTypeNode n) {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitNode(RefTypeNode n) throws TypeException {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitNode(IntTypeNode n) {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitNode(MethodTypeNode n) throws TypeException {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitNode(ClassTypeNode n) throws TypeException {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitNode(EmptyTypeNode n) throws TypeException {
		if (print) printNode(n);
		return null;
	}

	@Override
	public TypeNode visitSTentry(STentry entry) throws TypeException {
		if (print) printSTentry("type");
		return ckvisit(entry.type); 
	}

}