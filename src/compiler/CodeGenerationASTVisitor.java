package compiler;

import compiler.AST.*;
import compiler.lib.*;
import compiler.exc.*;

import java.util.ArrayList;
import java.util.List;

import static compiler.lib.FOOLlib.*;
import static svm.ExecuteVM.MEMSIZE;

public class CodeGenerationASTVisitor extends BaseASTVisitor<String, VoidException> {

  CodeGenerationASTVisitor() {}
  CodeGenerationASTVisitor(boolean debug) {super(false,debug);} //enables print for debugging

	List<List<String>> dispatchTables = new ArrayList<>();

	@Override
	public String visitNode(ProgLetInNode n) {
		if (print) printNode(n);
		String classCode = null;
		String declCode = null;
		for (Node cl : n.classlist) classCode=nlJoin(classCode,visit(cl));
		for (Node dec : n.declist) declCode=nlJoin(declCode,visit(dec));
		return nlJoin(
			"push 0",
			classCode,
			"/* end class code */",
			declCode, // generate code for declarations (allocation)
			"/* end decl code */",
			visit(n.exp),
			"halt",
			getCode()
		);
	}

	@Override
	public String visitNode(ProgNode n) {
		if (print) printNode(n);
		return nlJoin(
			visit(n.exp),
			"halt"
		);
	}

	@Override
	public String visitNode(LessEqualNode n) throws VoidException {
		String label1 = freshLabel();
		String label2 = freshLabel();
		return nlJoin(
				visit(n.left), // push the first element to check
				visit(n.right), // push the second element
				"bleq " + label1, // go to l1 in order to push true into stack (left <= right)
				"push 0", // otherwise push false
				"b " + label2, // jump to l2 to avoid l1
				label1 + ":",
				"push 1",
				label2 + ":"
		);
	}

	@Override
	public String visitNode(GreaterEqualNode n) throws VoidException {
		String label1 = freshLabel();
		String label2 = freshLabel();
		return nlJoin(
				visit(n.right), // push the first element to check
				visit(n.left), // push the second element
				"bleq " + label1, // go to l1 in order to push true (left >= right)
				"push 0", // otherwise false
				"b " + label2, // jump to l2 to avoid l1
				label1 + ":",
				"push 1",
				label2 + ":"
		);
	}

	@Override
	public String visitNode(OrNode n) throws VoidException {
		if (print) printNode(n);
		String label1 = freshLabel();
		String label2 = freshLabel();

		return nlJoin(
				visit(n.right), // push the operands
				visit(n.left),
				"bleq " + label1, // if right <= left it means that left operand leads
				visit(n.right), // otherwise the right one
				"b " + label2,
				label1 + ":",
				visit(n.left), // if left is true push true into stack, false otherwise
				label2 + ":"
		);
	}

	@Override
	public String visitNode(AndNode n) throws VoidException {
		if (print) printNode(n);

		return nlJoin(
				visit(n.left),
				visit(n.right),
				"mult" // and as mult operation between the two operands (0 o 1)
		);
	}

	@Override
	public String visitNode(NotNode n) throws VoidException {
		if (print) printNode(n);
		return nlJoin(
				// in order to negate a boolean (0 e 1) do the sub operation with 1
				// 1 is true 0 is false
				"push 1",
				visit(n.exp),
				"sub"
		);
	}

	@Override
	public String visitNode(DivNode n) throws VoidException {
		if (print) printNode(n);
		return nlJoin(
				visit(n.left),
				visit(n.right),
				"div"
		);
	}

	@Override
	public String visitNode(MinusNode n) throws VoidException {
		if (print) printNode(n);
		return nlJoin(
				visit(n.left),
				visit(n.right),
				"sub"
		);
	}

	@Override
	public String visitNode(EqualNode n) {
		if (print) printNode(n);
		String l1 = freshLabel();
		String l2 = freshLabel();
		return nlJoin(
				visit(n.left),
				visit(n.right),
				"beq "+l1, // check the operands are equal
				"push 0",
				"b "+l2,
				l1+":",
				"push 1",
				l2+":"
		);
	}

	@Override
	public String visitNode(TimesNode n) {
		if (print) printNode(n);
		return nlJoin(
				visit(n.left),
				visit(n.right),
				"mult"
		);
	}

	@Override
	public String visitNode(PlusNode n) {
		if (print) printNode(n);
		return nlJoin(
				visit(n.left),
				visit(n.right),
				"add"
		);
	}

	@Override
	public String visitNode(FunNode n) {
		if (print) printNode(n,n.id);
		String declCode = null, popDecl = null, popParl = null;
		for (Node dec : n.declist) {
			declCode = nlJoin(declCode,visit(dec));
			popDecl = nlJoin(popDecl,"pop");
		}
		for (int i=0;i<n.parlist.size();i++) popParl = nlJoin(popParl,"pop");
		String funl = freshFunLabel();
		putCode(
			nlJoin(
				funl+":",
				"cfp", // set $fp to $sp value
				"lra", // load $ra value (in order to return to caller)
				declCode, // generate code for local declarations (they use the new $fp!!!)
				visit(n.exp), // generate code for function body expression
				"stm", // set $tm to popped value (function result)
				popDecl, // remove local declarations from stack
				"sra", // set $ra to popped value
				"pop", // remove Access Link from stack
				popParl, // remove parameters from stack
				"sfp", // set $fp to popped value (Control Link)
				"ltm", // load $tm value (function result)
				"lra", // load $ra value
				"js"  // jump to to popped address
			)
		);
		return "push "+funl;
	}

	@Override
	public String visitNode(MethodNode n) throws VoidException {
		if (print) printNode(n,n.id);
		String declCode = null, popDecl = null, popParl = null;
		for (Node dec : n.declist) {
			declCode = nlJoin(declCode,visit(dec));
			popDecl = nlJoin(popDecl,"pop");
		}
		for (int i=0;i<n.parlist.size();i++) popParl = nlJoin(popParl,"pop");
		putCode(
				nlJoin(
						"/* method " + n.id + " declaration */",
						n.label+":",
						"cfp", // set $fp to $sp value
						"lra", // load $ra value
						declCode, // generate code for local declarations (they use the new $fp!!!)
						visit(n.exp), // generate code for function body expression
						"stm", // set $tm to popped value (function result)
						popDecl, // remove local declarations from stack
						"sra", // set $ra to popped value
						"pop", // remove Access Link from stack
						popParl, // remove parameters from stack
						"sfp", // set $fp to popped value (Control Link)
						"ltm", // load $tm value (function result)
						"lra", // load $ra value
						"js"  // jump to popped address
				)
		);
		return null;
	}

	@Override
	public String visitNode(ClassNode n) throws VoidException {
		if (print) printNode(n,n.id);
		ArrayList<String> dispatchTable = new ArrayList<>();
		if(!n.superId.isEmpty()) {
			var parentTable = dispatchTables.get(-n.superEntry.offset - 2);
			dispatchTable.addAll(parentTable);
		}
		for(MethodNode method : n.methodlist) {
			String freshLabel = freshFunLabel();
			method.label = freshLabel;
			int methodOffset = method.offset;
			if(methodOffset >= dispatchTable.size()) {
				// if non-override adds the method to the bottom of the list
				dispatchTable.add(freshLabel);
			} else {
				// if override substitutes the already present method
				dispatchTable.set(methodOffset, freshLabel);
			}
			visit(method);
		}

		String labels = "";
		for (String methodLabel : dispatchTable) {
			labels = nlJoin(labels,
					"/* method " + methodLabel + "*/",
					"push " +methodLabel, // push the label into the stack
					"lhp", // pusho hp into stack
					"sw", // pop two values: hp and label and memorize label into the address contained in hp register
					"push 1", // push 1 to increment hp
					"lhp", // push hp
					"add", // pop two values and do the sum: hp + 1
					"shp" // store the final result into hp (hp = hp + 1)
			);
		}

		dispatchTables.add(dispatchTable);

		return nlJoin(
				"/* class " + n.id + " declaration */",
				"lhp", // push the content of hp register to the top of the stack
				labels
		);
	}

	@Override
	public String visitNode(ClassCallNode n) throws VoidException {
		if (print) printNode(n,n.id);

		String argCode = null, getAR = null;
		for (int i=n.arglist.size()-1;i>=0;i--) argCode=nlJoin(argCode,visit(n.arglist.get(i)));
		for (int i = 0;i<n.nl-n.entry.nl;i++) getAR=nlJoin(getAR,"lw");
		return nlJoin(
				"/* method " + n.idMethod + " recall */",
				"lfp", // load Control Link (pointer to frame of function "id" caller)
				argCode, // generate code for argument expressions in reversed order
				"lfp", getAR, // retrieve address of frame containing "id" declaration
				// by following the static chain (of Access Links)
				"push " + n.entry.offset, // address of object's dispatch pointer
				"add",
				"lw",

				"stm", // set $tm to popped value (with the aim of duplicating top of stack)
				"ltm", // load Access Link (pointer to frame of function "id" declaration)
				"ltm", // duplicate top of stack
				"lw",

				"push "+n.methodEntry.offset,
				"add", // compute address of "id" declaration
				"lw", // load address of "id" function
				"js"  // jump to popped address (saving address of subsequent instruction in $ra)
		);
	}

	@Override
	public String visitNode(NewNode n) throws VoidException {
		if (print) printNode(n,n.id);

		String argCode = null;
		for (int i = 0; i < n.arglist.size(); i++)
			argCode=nlJoin(argCode,visit(n.arglist.get(i))
					,"/* campo classe */"
			);

		String pushOnHeapCode = null;
		for (int i=0;i<n.arglist.size();i++) {
			pushOnHeapCode = nlJoin(pushOnHeapCode,
					"lhp", // push hp into stack
					"sw", // pop two values: hp and label and memorize label into the address contained in hp register

					"lhp", // push hp
					"push 1", // push 1 to increment hp
					"add", // pop two values and do the sum: hp + 1
					"shp" // store the final result into hp (hp = hp + 1)
			);
		}

		return nlJoin(
				argCode,
				pushOnHeapCode, // push all the arg
				// uments on the heap code memory
				"push " + MEMSIZE,
				"push " + n.entry.offset,
				"add", // calculate the dispatch pointer
				"lw",
				"/* dispatch pointer write */",
				"lhp",
				"sw", // write the dispatch pointer into the address contained in hp register
				"/* object pointer load */",
				"lhp", // load the object pointer to be returned
				"/* hp increment */",
				"lhp", // push hp
				"push 1", // push 1 to increment hp
				"add", // pop two values and do the sum: hp + 1
				"shp" // store the final result into hp (hp = hp + 1)
		);

	}

	@Override
	public String visitNode(VarNode n) {
		if (print) printNode(n,n.id);
		return visit(n.exp);
	}

	@Override
	public String visitNode(PrintNode n) {
		if (print) printNode(n);
		return nlJoin(
			visit(n.exp),
			"print"
		);
	}

	@Override
	public String visitNode(IfNode n) {
		if (print) printNode(n);

	 	String label1 = freshLabel();
	 	String label2 = freshLabel();
		return nlJoin(
			visit(n.cond),
			"push 1",
			"beq "+label1, // check the condition is true
			visit(n.el), // visit else branch
			"b "+label2, // jump to then branch
			label1+":",
			visit(n.th), // visit then branch
			label2+":"
		);
	}

	@Override
	public String visitNode(CallNode n) {
		if (print) printNode(n,n.id);
		String argCode = null, getAR = null;

		// push the arguments
		for (int i=n.arglist.size()-1;i>=0;i--)
			argCode=nlJoin(argCode,visit(n.arglist.get(i)));

		// AR ascent
		for (int i = 0;i<n.nl-n.entry.nl;i++)
			getAR=nlJoin(getAR,"lw");

		return nlJoin(
			"lfp", // load Control Link (pointer to frame of function "id" caller)
			argCode, // generate code for argument expressions in reversed order
			"lfp", // load CL again to start the ascent of AL (addresses of previous ARs)
			getAR, // retrieve address of frame containing "id" declaration
				   // by following the static chain (of Access Links)
            "stm", // set $tm to popped value (with the aim of duplicating top of stack)
            "ltm", // load Access Link (pointer to frame of function "id" declaration)
            "ltm", // duplicated top of stack (first load to tm register and then load twice on top of the stack)

            "push " + n.entry.offset,
			"add", // compute address of "id" declaration
			"lw", // load address of "id" function to execute it
            "js"  // jump to popped address (saving address of subsequent instruction in $ra)
		);
	}

	@Override
	public String visitNode(IdNode n) {
		if (print) printNode(n,n.id);
		String getAR = null;
		for (int i = 0;i<n.nl-n.entry.nl;i++) getAR=nlJoin(getAR,"lw");
		return nlJoin(
			"lfp", getAR, // retrieve address of frame containing "id" declaration
			              // by following the static chain (of Access Links)
			"push "+n.entry.offset,
			"add", // compute address of "id" declaration
			"lw" // load value of "id" variable
		);
	}

	@Override
	public String visitNode(BoolNode n) {
		if (print) printNode(n,n.val.toString());
		return "push "+(n.val?1:0);
	}

	@Override
	public String visitNode(IntNode n) {
		if (print) printNode(n,n.val.toString());
		return "push "+n.val;
	}

	@Override
	public String visitNode(EmptyNode n) throws VoidException {
		if (print) printNode(n);
		return "push -1";
	}
}