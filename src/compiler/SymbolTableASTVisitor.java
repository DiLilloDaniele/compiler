package compiler;

import java.util.*;
import java.util.stream.Collectors;

import compiler.AST.*;
import compiler.exc.*;
import compiler.lib.*;

public class SymbolTableASTVisitor extends BaseASTVisitor<Void,VoidException> {
	
	private List<Map<String, STentry>> symTable = new ArrayList<>();
	private Map<String, Map<String, STentry>> classTable = new HashMap<>();
	private int nestingLevel=0; // current nesting level
	private int decOffset=-2; // counter for offset of local declarations at current nesting level 
	int stErrors=0;
	private Set<String> symbolIDs; // per ottimizzazioni

	SymbolTableASTVisitor() {}
	SymbolTableASTVisitor(boolean debug) {super(debug);} // enables print for debugging

	private STentry stLookup(String id) {
		int j = nestingLevel;
		STentry entry = null;
		while (j >= 0 && entry == null) 
			entry = symTable.get(j--).get(id);	
		return entry;
	}

	@Override
	public Void visitNode(ProgLetInNode n) {
		if (print) printNode(n);
		Map<String, STentry> hm = new HashMap<>();
		symTable.add(hm);
		// visit classes and declarations
		for (Node dec : n.classlist) visit(dec);
	    for (Node dec : n.declist) visit(dec);
		visit(n.exp);
		symTable.remove(0);
		return null;
	}

	@Override
	public Void visitNode(ProgNode n) {
		if (print) printNode(n);
		visit(n.exp);
		return null;
	}
	
	@Override
	public Void visitNode(FunNode n) {
		if (print) printNode(n);
		Map<String, STentry> hm = symTable.get(nestingLevel);
		List<TypeNode> parTypes = new ArrayList<>();  
		for (ParNode par : n.parlist) parTypes.add(par.getType()); 
		STentry entry = new STentry(nestingLevel, new ArrowTypeNode(parTypes,n.retType),decOffset--);
		// add the id into the symtable
		if (hm.put(n.id, entry) != null) {
			System.out.println("Fun id " + n.id + " at line "+ n.getLine() +" already declared");
			stErrors++;
		}
		// create a new hashmap for the symtable
		nestingLevel++;
		Map<String, STentry> hmn = new HashMap<>();
		symTable.add(hmn);
		int prevNLDecOffset=decOffset; // stores counter for offset of declarations at previous nesting level 
		decOffset=-2;
		
		int parOffset=1;
		for (ParNode par : n.parlist)
			if (hmn.put(par.id, new STentry(nestingLevel,par.getType(),parOffset++)) != null) {
				System.out.println("Par id " + par.id + " at line "+ n.getLine() +" already declared");
				stErrors++;
			}
		for (Node dec : n.declist) visit(dec);
		visit(n.exp);
		// remove the hashmap and exit the scope
		symTable.remove(nestingLevel--);
		decOffset=prevNLDecOffset; // restores counter for offset of declarations at previous nesting level 
		return null;
	}

	@Override
	public Void visitNode(ClassNode n) throws VoidException {
		if (print) printNode(n);

		symbolIDs = new HashSet<>();

		Map<String, STentry> hm = symTable.get(0);

		ClassTypeNode classTypeNode;
		ClassTypeNode superType = null;
		Map<String, STentry> virtualTable; // virtual table

		if(Objects.equals(n.superId, "")) {
			ArrayList<TypeNode> fields = new ArrayList<>();
			ArrayList<MethodTypeNode> methods = new ArrayList<>();
			classTypeNode = new ClassTypeNode(fields, methods);
			virtualTable = new HashMap<>();
		} else {
			if(!classTable.containsKey(n.superId)) {
				System.out.println("Superclass id " + n.id + " at line "+ n.getLine() +" not declared");
				stErrors++;
			}

			// pick up the STEntry of the super class, that is into the nesting level 0
			n.superEntry = hm.get(n.superId);
			// memorizzo il tipo della classe padre
			superType = (ClassTypeNode) n.superEntry.type;

			// initialize the ClassTypeNode with the inherited fields and methods
			classTypeNode = new ClassTypeNode(
					new ArrayList<>(superType.allFields),
					new ArrayList<>(superType.allMethods)
			);
			virtualTable = new HashMap<>(classTable.get(n.superId));
		}

		// create a new entry for the class type
		STentry entry = new STentry(nestingLevel, classTypeNode, decOffset--);
		n.type = classTypeNode;
		if (hm.put(n.id, entry) != null) {
			System.out.println("Class id " + n.id + " at line "+ n.getLine() +" already declared");
			stErrors++;
		}

		// create a new hashmap for the virtual table to be inserted into the symbol table
		nestingLevel++;
		// the virtual table contains all the fields and methods definitions of a class
		classTable.put(n.id, virtualTable);
		symTable.add(virtualTable);

		int prevNLDecOffset=decOffset; // stores counter for offset of declarations at previous nesting level
		// nel layout deciso a priori, decOffset indica il valore
		// di offset per i metodi della classe, che per definizione parte da 0, per creare correttamente il layout dello HEAP
		// da usare in fase runtime di creazione di oggetti

		int fieldOffset = (Objects.equals(n.superId, "")) ? -1 : -superType.allFields.size() - 1;
		for (int i=0; i<n.fieldlist.size(); i++) {
			FieldNode field = n.fieldlist.get(i);
			if(!symbolIDs.add(field.id)) {
				System.out.println("Field id " + field.id + " at line " + n.getLine() + " already declared");
				stErrors++;
			} else {
				if(!virtualTable.containsKey(field.id)) {
					// non c'è override
					virtualTable.put(field.id, new STentry(nestingLevel, field.getType(), fieldOffset));
					field.offset = fieldOffset;
					fieldOffset--;
					classTypeNode.allFields.add(field.getType());
				} else {
					// override
					STentry oldEntry = virtualTable.get(field.id);
					// invalid overriding
					if(oldEntry.type instanceof MethodTypeNode) {
						System.out.println("Field " + n.id+"."+field.id + " cannot override a method in superclass");
						stErrors++;
					} else {
						// correct overriding
						virtualTable.put(field.id, new STentry(nestingLevel, field.getType(), oldEntry.offset));
						field.offset = oldEntry.offset;
						field.isOverride = true;
						System.out.println("OVERRIDE FIEELD");
						classTypeNode.allFields.set(Math.abs(oldEntry.offset) - 1, field.getType());
					}
				}
			}
		}

		decOffset = (Objects.equals(n.superId, "")) ? 0 : superType.allMethods.size();;

		for(int i=0; i<n.methodlist.size(); i++) {
			MethodNode method = n.methodlist.get(i);
			visit(method);
			if(!symbolIDs.add(method.id)) {
				System.out.println("Method id " + n.id+"."+method.id + " at line " + n.getLine() + " already declared");
				stErrors++;
			} else {
				if(!virtualTable.containsKey(method.id)) {
					// not override
					virtualTable.put(method.id, new STentry(nestingLevel, method.getType(), decOffset));
					// update all fields of the ClassTypeNode
					method.offset = decOffset;
					decOffset++;
					classTypeNode.allMethods.add(new MethodTypeNode(new ArrowTypeNode(method.parlist.stream().map(DecNode::getType).collect(Collectors.toList()), method.retType)));
				} else {
					// override
					STentry oldEntry = virtualTable.get(method.id);
					// invalid overriding
					if(!(oldEntry.type instanceof MethodTypeNode)) {
						System.out.println("Method " + n.id+"."+method.id + " cannot override a field in superclass. It is " + (oldEntry.type));
						stErrors++;
					} else {
						// correct overriding
						virtualTable.put(method.id, new STentry(nestingLevel, method.getType(), oldEntry.offset));
						method.offset = oldEntry.offset;
						classTypeNode.allMethods.set(oldEntry.offset,
								new MethodTypeNode(
										new ArrowTypeNode(method.parlist.stream().map(DecNode::getType).collect(Collectors.toList()), method.retType))
						);
					}
				}
			}
		}

		symTable.remove(nestingLevel--);
		decOffset=prevNLDecOffset; // restores counter for offset of declarations at previous nesting level
		return null;
	}

	@Override
	public Void visitNode(MethodNode n) throws VoidException {
		if (print) printNode(n);
		// retrieve the symbol table of the class, that is the virtual table
		Map<String, STentry> hm = symTable.get(nestingLevel);
		List<TypeNode> parTypes = new ArrayList<>();
		for (ParNode par : n.parlist) parTypes.add(par.getType());
		n.offset = decOffset;

		// Creating and setting the method type
		MethodTypeNode methodType = new MethodTypeNode(new ArrowTypeNode(parTypes, n.retType));
		n.setType(methodType);

		nestingLevel++;
		Map<String, STentry> hmn = new HashMap<>();
		symTable.add(hmn);
		int prevNLDecOffset=decOffset; // stores counter for offset of declarations at previous nesting level
		decOffset=1;

		for (ParNode par : n.parlist)
			if (hmn.put(par.id, new STentry(nestingLevel,par.getType(),decOffset++)) != null) {
				System.out.println("Par id " + par.id + " at line "+ n.getLine() +" already declared");
				stErrors++;
			}
		for (Node dec : n.declist) visit(dec);
		visit(n.exp);

		symTable.remove(nestingLevel--);
		decOffset=prevNLDecOffset; // restores counter for offset of declarations at previous nesting level
		return null;
	}

	@Override
	public Void visitNode(ClassCallNode n) throws VoidException {
		if (print) printNode(n);

		STentry entry = stLookup(n.id);
		if (entry == null) {
			System.out.println("Object id " + n.id + " at line "+ n.getLine() + " not declared");
			stErrors++;
			return null;
		} else {
			n.entry = entry;
			n.nl = nestingLevel;
		}

		// retrieve the information of the class id and the method entry
		String classId = ((RefTypeNode)entry.type).classId;
		STentry methodEntry = classTable.get(classId).get(n.idMethod);
		n.methodEntry = methodEntry;

		// visit the arguments
		for (Node arg : n.arglist) visit(arg);

		return null;
	}

	@Override
	public Void visitNode(VarNode n) {
		if (print) printNode(n);
		visit(n.exp);
		Map<String, STentry> hm = symTable.get(nestingLevel);
		STentry entry = new STentry(nestingLevel,n.getType(),decOffset--);

		if (hm.put(n.id, entry) != null) {
			System.out.println("Var id " + n.id + " at line "+ n.getLine() +" already declared");
			stErrors++;
		}
		return null;
	}

	@Override
	public Void visitNode(NewNode n) throws VoidException {
		if (print) printNode(n);

		Map<String, STentry> classEntry = classTable.get(n.id);
		if(classEntry == null) {
			System.out.println("Class id " + n.id + " at line "+ n.getLine() +" not declared");
			stErrors++;
		} else {
			STentry entry = symTable.get(0).get(n.id);
			n.entry = entry;
			n.nl = nestingLevel;
		}

		for (Node arg : n.arglist) visit(arg);
		return null;
	}

	@Override
	public Void visitNode(PrintNode n) {
		if (print) printNode(n);
		visit(n.exp);
		return null;
	}

	@Override
	public Void visitNode(IfNode n) {
		if (print) printNode(n);
		visit(n.cond);
		visit(n.th);
		visit(n.el);
		return null;
	}

	@Override
	public Void visitNode(EmptyNode n) throws VoidException {
		if (print) printNode(n);
		return null;
	}

	@Override
	public Void visitNode(LessEqualNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(GreaterEqualNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(OrNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(AndNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(DivNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(MinusNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(EqualNode n) {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}
	
	@Override
	public Void visitNode(TimesNode n) {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}
	
	@Override
	public Void visitNode(PlusNode n) {
		if (print) printNode(n);
		visit(n.left);
		visit(n.right);
		return null;
	}

	@Override
	public Void visitNode(CallNode n) {
		if (print) printNode(n);
		STentry entry = stLookup(n.id);
		if (entry == null) {
			System.out.println("Fun id " + n.id + " at line "+ n.getLine() + " not declared");
			stErrors++;
		} else {
			n.entry = entry;
			n.nl = nestingLevel;
		}
		for (Node arg : n.arglist) visit(arg);
		return null;
	}

	@Override
	public Void visitNode(IdNode n) {
		if (print) printNode(n);
		STentry entry = stLookup(n.id);

		if (entry == null) {
			System.out.println("Var or Par id " + n.id + " at line "+ n.getLine() + " not declared");
			stErrors++;
		} else {
			n.entry = entry;
			n.nl = nestingLevel;
		}
		return null;
	}

	@Override
	public Void visitNode(BoolNode n) {
		if (print) printNode(n, n.val.toString());
		return null;
	}

	@Override
	public Void visitNode(IntNode n) {
		if (print) printNode(n, n.val.toString());
		return null;
	}

	@Override
	public Void visitNode(NotNode n) throws VoidException {
		if (print) printNode(n);
		visit(n.node);
		return null;
	}
}
