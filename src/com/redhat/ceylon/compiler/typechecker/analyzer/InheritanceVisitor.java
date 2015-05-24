package com.redhat.ceylon.compiler.typechecker.analyzer;

import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkAssignable;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkCasesDisjoint;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkIsExactly;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.getTypedDeclaration;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.inLanguageModule;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.inSameModule;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.typeDescription;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.typeNamesAsIntersection;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.name;
import static com.redhat.ceylon.model.typechecker.model.Util.addToIntersection;
import static com.redhat.ceylon.model.typechecker.model.Util.areConsistentSupertypes;
import static com.redhat.ceylon.model.typechecker.model.Util.isTypeUnknown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import com.redhat.ceylon.model.typechecker.model.Class;
import com.redhat.ceylon.model.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.model.typechecker.model.Constructor;
import com.redhat.ceylon.model.typechecker.model.Declaration;
import com.redhat.ceylon.model.typechecker.model.Interface;
import com.redhat.ceylon.model.typechecker.model.IntersectionType;
import com.redhat.ceylon.model.typechecker.model.ProducedType;
import com.redhat.ceylon.model.typechecker.model.Scope;
import com.redhat.ceylon.model.typechecker.model.TypeAlias;
import com.redhat.ceylon.model.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.model.typechecker.model.TypeParameter;
import com.redhat.ceylon.model.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.model.typechecker.model.Unit;
import com.redhat.ceylon.model.typechecker.model.UnknownType;

public class InheritanceVisitor extends Visitor {
    
    @Override public void visit(Tree.TypeDeclaration that) {
        validateSupertypes(that, that.getDeclarationModel());
        super.visit(that);
    }

    @Override public void visit(Tree.ObjectDefinition that) {
        validateSupertypes(that, 
                that.getDeclarationModel().getType().getDeclaration());
        super.visit(that);
    }

    @Override public void visit(Tree.ObjectArgument that) {
        validateSupertypes(that, 
                that.getAnonymousClass());
        super.visit(that);
    }

    @Override public void visit(Tree.ObjectExpression that) {
        validateSupertypes(that, 
                that.getAnonymousClass());
        super.visit(that);
    }

    @Override public void visit(Tree.TypeConstraint that) {
        super.visit(that);
        validateUpperBounds(that, that.getDeclarationModel());
    }

    private void validateSupertypes(Node that, 
            TypeDeclaration td) {
        if (!(td instanceof TypeAlias)) {
            List<ProducedType> supertypes = td.getType().getSupertypes();
            for (int i=0; i<supertypes.size(); i++) {
                ProducedType st1 = supertypes.get(i);
                for (int j=i+1; j<supertypes.size(); j++) {
                    ProducedType st2 = supertypes.get(j);
                    checkSupertypeIntersection(that, td, st1, st2); //note: sets td.inconsistentType by side-effect
                }
            }
        }
    }
    private void checkSupertypeIntersection(Node that,
            TypeDeclaration td, ProducedType st1, ProducedType st2) {
        if (st1.getDeclaration().equals(st2.getDeclaration()) /*&& !st1.isExactly(st2)*/) {
            Unit unit = that.getUnit();
            if (!areConsistentSupertypes(st1, st2, unit)) {
                that.addError(typeDescription(td, unit) +
                        " has the same parameterized supertype twice with incompatible type arguments: '" +
                        st1.getProducedTypeName(unit) + " & " + 
                        st2.getProducedTypeName(unit) + "'");
               td.setInconsistentType(true);
            }
        }
    }

    private void validateUpperBounds(Tree.TypeConstraint that,
            TypeDeclaration td) {
        if (!td.isInconsistentType()) {
            Unit unit = that.getUnit();
            List<ProducedType> upperBounds = td.getSatisfiedTypes();
            List<ProducedType> list = 
                    new ArrayList<ProducedType>
                        (upperBounds.size());
            for (ProducedType st: upperBounds) {
                addToIntersection(list, st, unit);
            }
            IntersectionType it = new IntersectionType(unit);
            it.setSatisfiedTypes(list);
            if (it.canonicalize().getType().isNothing()) {
                that.addError(typeDescription(td, unit) + 
                        " has unsatisfiable upper bound constraints: the constraints '" + 
                        typeNamesAsIntersection(upperBounds, unit) + 
                        "' cannot be satisfied by any type except 'Nothing'");
            }
        }
    }

    @Override 
    public void visit(Tree.ExtendedType that) {
        super.visit(that);
        
        TypeDeclaration td = 
                (TypeDeclaration) that.getScope();
        if (!td.isAlias()) {
            Tree.SimpleType et = that.getType();
            if (et!=null) {
                Tree.InvocationExpression ie = 
                        that.getInvocationExpression();
                Class clazz = (Class) td;
                boolean hasConstructors = 
                        clazz.hasConstructors();
                boolean anonymous = clazz.isAnonymous();
                if (ie==null) { 
                    if (!hasConstructors || anonymous) {
                        et.addError("missing instantiation arguments");
                    }
                }
                else {
                    if (hasConstructors && !anonymous) {
                        et.addError("unnecessary instantiation arguments");
                    }
                }
                
                Unit unit = that.getUnit();

                ProducedType type = et.getTypeModel();
                if (type!=null) {
                    checkSelfTypes(et, td, type);
                    checkExtensionOfMemberType(et, td, type);
                    //checkCaseOfSupertype(et, td, type);
                    TypeDeclaration etd = 
                            td.getExtendedTypeDeclaration();
                    TypeDeclaration aetd = type.getDeclaration();
                    if (aetd instanceof Constructor &&
                            aetd.isAbstract()) {
                        et.addError("extends a partial constructor: '" +
                                aetd.getName(unit) + 
                                "' is declared abstract");
                    }
                    while (etd!=null && etd.isAlias()) {
                        etd = etd.getExtendedTypeDeclaration();
                    }
                    if (etd!=null) {
                        if (etd.isFinal()) {
                            et.addError("extends a final class: '" + 
                                    etd.getName(unit) + 
                                    "' is declared final");
                        }
                        if (etd.isSealed() && 
                                !inSameModule(etd, unit)) {
                            String moduleName = 
                                    etd.getUnit()
                                        .getPackage()
                                        .getModule()
                                        .getNameAsString();
                            et.addError("extends a sealed class in a different module: '" +
                                    etd.getName(unit) + 
                                    "' in '" + moduleName + 
                                    "' is sealed");
                        }
                    }
                }
                checkSupertypeVarianceAnnotations(et);
            }
        }
    }

    @Override 
    public void visit(Tree.SatisfiedTypes that) {
        super.visit(that);
        TypeDeclaration td = 
                (TypeDeclaration) that.getScope();
        if (td.isAlias()) {
            return;
        }
        Set<TypeDeclaration> set = 
                new HashSet<TypeDeclaration>();
        if (td.getSatisfiedTypes().isEmpty()) {
            return; //handle undecidable case
        }
        
        Unit unit = that.getUnit();
        
        for (Tree.StaticType t: that.getTypes()) {
            ProducedType type = t.getTypeModel();
            if (type!=null && type.getDeclaration()!=null) {
                type = type.resolveAliases();
                TypeDeclaration std = type.getDeclaration();
                if (td instanceof ClassOrInterface &&
                        !inLanguageModule(that.getUnit())) {
                    if (unit.isCallableType(type)) {
                        t.addError("satisfies 'Callable'");
                    }
                    TypeDeclaration cad = 
                            unit.getConstrainedAnnotationDeclaration();
                    if (type.getDeclaration().equals(cad)) {
                        t.addError("directly satisfies 'ConstrainedAnnotation'");
                    }
                }
                if (!set.add(type.getDeclaration())) {
                    //this error is not really truly necessary
                    //but the spec says it is an error, and
                    //the backend doesn't like it
                    t.addError("duplicate satisfied type: '" + 
                            type.getDeclaration().getName(unit) +
                            "' of '" + td.getName() + "'");
                }
                if (td instanceof ClassOrInterface && 
                        std.isSealed() && 
                        !inSameModule(std, unit)) {
                    String moduleName = 
                            std.getUnit()
                                .getPackage()
                                .getModule()
                                .getNameAsString();
                    t.addError("satisfies a sealed interface in a different module: '" +
                            std.getName(unit) + "' in '" + moduleName + "'");
                }
                checkSelfTypes(t, td, type);
                checkExtensionOfMemberType(t, td, type);
                /*if (!(td instanceof TypeParameter)) {
                    checkCaseOfSupertype(t, td, type);
                }*/
            }
            if (t instanceof Tree.SimpleType) {
                Tree.SimpleType st = (Tree.SimpleType) t;
                checkSupertypeVarianceAnnotations(st);
            }
        }
    }

    @Override 
    public void visit(Tree.CaseTypes that) {
        super.visit(that);
        
        //this forces every case to be a subtype of the
        //enumerated type, so that we can make use of the
        //enumerated type is equivalent to its cases
        TypeDeclaration td = 
                (TypeDeclaration) 
                    that.getScope();
        
        //TODO: get rid of this awful hack:
        List<ProducedType> cases = td.getCaseTypes();
        td.setCaseTypes(null);
        
        if (td instanceof TypeParameter) {
            for (Tree.StaticType t: that.getTypes()) {
                for (Tree.StaticType ot: that.getTypes()) {
                    if (t==ot) break;
                    checkCasesDisjoint(
                            t.getTypeModel(), 
                            ot.getTypeModel(), 
                            ot);
                }
            }
        }
        else {
            Unit unit = that.getUnit();
            Set<TypeDeclaration> typeSet = 
                    new HashSet<TypeDeclaration>();
            for (Tree.StaticType ct: that.getTypes()) {
                ProducedType type = ct.getTypeModel();
                if (!isTypeUnknown(type)) {
                    type = type.resolveAliases();
                    TypeDeclaration ctd = type.getDeclaration();
                    if (!typeSet.add(ctd)) {
                        //this error is not really truly necessary
                        ct.addError("duplicate case type: '" + 
                                ctd.getName(unit) + "' of '" + 
                                td.getName() + "'");
                    }
                    if (!(ctd instanceof TypeParameter)) {
                        //it's not a self type
                        if (checkDirectSubtype(td, ct, type)) {
                            checkAssignable(type, td.getType(), ct,
                                    getCaseTypeExplanation(td, type));
                        }
                        //note: this is a better, faster way to call 
                        //      validateEnumeratedSupertypeArguments()
                        //      but unfortunately it winds up displaying
                        //      the error on the wrong node, confusing
                        //      the user
                        /*ProducedType supertype = type.getDeclaration().getType().getSupertype(td);
                        validateEnumeratedSupertypeArguments(t, type.getDeclaration(), supertype);*/
                    }
                    if (ctd instanceof ClassOrInterface && 
                            ct instanceof Tree.SimpleType) {
                        Tree.SimpleType s = 
                                (Tree.SimpleType) ct;
                        Tree.TypeArgumentList tal = 
                                s.getTypeArgumentList();
                        if (tal!=null) {
                            List<Tree.Type> args = 
                                    tal.getTypes();
                            List<TypeParameter> typeParameters = 
                                    ctd.getTypeParameters();
                            for (int i=0; 
                                    i<args.size() && 
                                    i<typeParameters.size(); 
                                    i++) {
                                Tree.Type arg = args.get(i);
                                TypeParameter typeParameter = 
                                        ctd.getTypeParameters()
                                            .get(i);
                                ProducedType argType = 
                                        arg.getTypeModel();
                                if (argType!=null) {
                                    TypeDeclaration argTypeDec = 
                                            argType.getDeclaration();
                                    if (argTypeDec instanceof TypeParameter) {
                                        TypeParameter tp = 
                                                (TypeParameter) 
                                                    argTypeDec;
                                        if (!tp.getDeclaration().equals(td)) {
                                            arg.addError("type argument is not a type parameter of the enumerated type: '" +
                                                    argTypeDec.getName() + 
                                                    "' is not a type parameter of '" + 
                                                    td.getName());
                                        }
                                    }
                                    else if (typeParameter.isCovariant()) {
                                        checkAssignable(typeParameter.getType(), 
                                                argType, arg, 
                                                "type argument not an upper bound of the type parameter");
                                    }
                                    else if (typeParameter.isContravariant()) {
                                        checkAssignable(argType, 
                                                typeParameter.getType(), arg, 
                                                "type argument not a lower bound of the type parameter");
                                    }
                                    else {
                                        arg.addError("type argument is not a type parameter of the enumerated type: '" +
                                                argTypeDec.getName() + "'");
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Set<Declaration> valueSet = 
                    new HashSet<Declaration>();
            for (Tree.BaseMemberExpression bme: 
                    that.getBaseMemberExpressions()) {
                TypedDeclaration d = 
                        getTypedDeclaration(bme.getScope(), 
                                name(bme.getIdentifier()), 
                                null, false, unit);
                ProducedType type = d.getType();
                if (d!=null && !valueSet.add(d)) {
                    //this error is not really truly necessary
                    bme.addError("duplicate case: '" + 
                            d.getName(unit) + 
                            "' of '" + td.getName() + "'");
                }
                if (d!=null && type!=null && 
                        !type.getDeclaration()
                            .isAnonymous()) {
                    bme.addError("case must be a toplevel anonymous class: '" + 
                            d.getName(unit) + "' is not an anonymous class");
                }
                else if (d!=null && !d.isToplevel()) {
                    bme.addError("case must be a toplevel anonymous class: '" + 
                            d.getName(unit) + "' is not toplevel");
                }
                if (type!=null) {
                    if (checkDirectSubtype(td, bme, type)) {
                        checkAssignable(type, td.getType(), bme, 
                                getCaseTypeExplanation(td, type));
                    }
                }
            }
        }
        
        //TODO: get rid of this awful hack:
        td.setCaseTypes(cases);
    }

    @Override 
    public void visit(Tree.DelegatedConstructor that) {
        super.visit(that);
        
        TypeDeclaration constructor = 
                (TypeDeclaration) that.getScope();
        Scope container = constructor.getContainer();
        Tree.SimpleType type = that.getType();
        if (type!=null &&
                constructor instanceof Constructor &&
                container instanceof Class) {
            Class containingClass = (Class) container;
            Class superclass = 
                    containingClass.getExtendedTypeDeclaration();
            if (superclass!=null) {
                Unit unit = that.getUnit();
                ProducedType extendedType = 
                        containingClass.getExtendedType();
                ProducedType constructedType = 
                        type.getTypeModel();
                Declaration delegate = 
                        type.getDeclarationModel();
                if (delegate instanceof Constructor) {
                    Constructor c = (Constructor) delegate;
                    if (c.equals(constructor)) {
                        type.addError("constructor delegates to itself: '" +
                                c.getName() + "'");
                    }
                    ClassOrInterface delegatedType = 
                            c.getExtendedTypeDeclaration();
                    if (superclass.equals(delegatedType)) {
                        checkIsExactly(
                                constructedType.getExtendedType(), 
                                extendedType, type, 
                                "type arguments must match type arguments in extended class expression");
                    }
                    else if (containingClass.equals(delegatedType)) {
                        if (type instanceof Tree.QualifiedType) {
                            Tree.QualifiedType qt = 
                                    (Tree.QualifiedType) type;
                            checkIsExactly(
                                    constructedType.getQualifyingType(), 
                                    containingClass.getType(), 
                                    qt.getOuterType(), 
                                    "type arguments must be the type parameters of this class");
                        }
                    }
                    else {
                        type.addError("not a constructor of the immediate superclass: '" +
                                delegate.getName(unit) + 
                                "' is not a constructor of '" + 
                                superclass.getName(unit) + "'");
                    }
                }
                else if (delegate instanceof Class) {
                    if (superclass.equals(delegate)) {
                        checkIsExactly(constructedType, 
                                extendedType, type, 
                                "type arguments must match type arguments in extended class expression");
                    }
                    else if (containingClass.equals(delegate)) {
                        checkIsExactly(constructedType, 
                                containingClass.getType(), type, 
                                "type arguments must be the type parameters of this class");
                    }
                    else {
                        type.addError("does not instantiate the immediate superclass: '" +
                                delegate.getName(unit) + "' is not '" + 
                                superclass.getName(unit) + "'");
                    }
                }
            }
        }
    }

    private static boolean checkDirectSubtype(TypeDeclaration td, 
            Node node, ProducedType type) {
        boolean found = false;
        TypeDeclaration ctd = type.getDeclaration();
        if (td instanceof Interface) {
            for (ProducedType st: ctd.getSatisfiedTypes()) {
                if (st!=null && 
                        st.resolveAliases().getDeclaration()
                            .equals(td)) {
                    found = true;
                }
            }
        }
        else if (td instanceof Class) {
            ProducedType et = ctd.getExtendedType();
            if (et!=null && 
                    et.resolveAliases().getDeclaration()
                        .equals(td)) {
                found = true;
            }
        }
        if (!found) {
            node.addError("case type is not a direct subtype of enumerated type: " + 
                    ctd.getName(node.getUnit()));
        }
        return found;
    }

    private String getCaseTypeExplanation(TypeDeclaration td, 
            ProducedType type) {
        String message = "case type must be a subtype of enumerated type";
        if (!td.getTypeParameters().isEmpty() &&
                type.getDeclaration().inherits(td)) {
            message += " for every type argument of the generic enumerated type";
        }
        return message;
    }

    private void checkExtensionOfMemberType(Node that, 
            TypeDeclaration td, ProducedType type) {
        ProducedType qt = type.getQualifyingType();
        if (qt!=null && td instanceof ClassOrInterface) {
            Unit unit = that.getUnit();
            TypeDeclaration d = type.getDeclaration();
            if (d.isStaticallyImportable() ||
                    d instanceof Constructor) {
                checkExtensionOfMemberType(that, td, qt);
            }
            else {
                Scope s = td;
                while (s!=null) {
                    s = s.getContainer();
                    if (s instanceof TypeDeclaration) {
                        TypeDeclaration otd = 
                                (TypeDeclaration) s;
                        if (otd.getType().isSubtypeOf(qt)) {
                            return;
                        }
                    }
                }
                that.addError("qualifying type '" + qt.getProducedTypeName(unit) + 
                        "' of supertype '" + type.getProducedTypeName(unit) + 
                        "' is not an outer type or supertype of any outer type of '" +
                        td.getName(unit) + "'");
            }
        }
    }
    
    private void checkSelfTypes(Tree.StaticType that, 
            TypeDeclaration td, ProducedType type) {
        if (!(td instanceof TypeParameter)) { //TODO: is this really ok?!
            List<TypeParameter> params = 
                    type.getDeclaration().getTypeParameters();
            List<ProducedType> args = 
                    type.getTypeArgumentList();
            Unit unit = that.getUnit();
            for (int i=0; i<params.size(); i++) {
                TypeParameter param = params.get(i);
                if ( param.isSelfType() && args.size()>i ) {
                    ProducedType arg = args.get(i);
                    if (arg==null) {
                        arg = new UnknownType(unit).getType(); 
                    }
                    TypeDeclaration std = 
                            param.getSelfTypedDeclaration();
                    ProducedType at;
                    TypeDeclaration mtd;
                    if (param.getContainer().equals(std)) {
                        at = td.getType();
                        mtd = td;
                    }
                    else {
                        //TODO: lots wrong here?
                        mtd = (TypeDeclaration) 
                                td.getMember(std.getName(), 
                                        null, false);
                        at = mtd==null ? null : mtd.getType();
                    }
                    if (at!=null && !at.isSubtypeOf(arg) && 
                            !(mtd.getSelfType()!=null && 
                                mtd.getSelfType().isExactly(arg))) {
                        String help;
                        TypeDeclaration ad = arg.getDeclaration();
                        if (ad instanceof TypeParameter &&
                                ((TypeParameter) ad).getDeclaration().equals(td)) {
                            help = " (try making " + ad.getName() + 
                                    " a self type of " + td.getName() + ")";
                        }
                        else if (ad instanceof Interface) {
                            help = " (try making " + td.getName() + 
                                    " satisfy " + ad.getName() + ")";
                        }
                        else if (ad instanceof Class && td instanceof Class) {
                            help = " (try making " + td.getName() + 
                                    " extend " + ad.getName() + ")";
                        }
                        else {
                            help = "";
                        }
                        that.addError("type argument does not satisfy self type constraint on type parameter '" +
                                param.getName() + "' of '" + 
                                type.getDeclaration().getName(unit) + "': '" +
                                arg.getProducedTypeName(unit) + 
                                "' is not a supertype or self type of '" + 
                                td.getName(unit) + "'" + help);
                    }
                }
            }
        }
    }

    private void checkSupertypeVarianceAnnotations(Tree.SimpleType et) {
        Tree.TypeArgumentList tal = 
                et.getTypeArgumentList();
        if (tal!=null) {
            for (Tree.Type t: tal.getTypes()) {
                if (t instanceof Tree.StaticType) {
                    Tree.StaticType st = (Tree.StaticType) t;
                    Tree.TypeVariance variance = 
                            st.getTypeVariance();
                    if (variance!=null) {
                        variance.addError("supertype expression may not specify variance");
                    }
                }
            }
        }
    }
}
