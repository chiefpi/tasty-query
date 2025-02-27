package tastyquery

import tastyquery.Contexts
import tastyquery.ast.Constants.{ClazzTag, Constant, IntTag, NullTag}
import tastyquery.ast.Names.*
import tastyquery.ast.Symbols.{NoSymbol, Symbol}
import tastyquery.ast.Trees.*
import tastyquery.ast.TypeTrees.*
import tastyquery.ast.Types.*
import tastyquery.reader.TastyUnpickler
import dotty.tools.tasty.TastyFormat.NameTags

class ReadTreeSuite extends BaseUnpicklingSuite(withClasses = false, withStdLib = false, allowDeps = false) {
  import BaseUnpicklingSuite.Decls.*

  type StructureCheck = PartialFunction[Tree, Unit]
  type TypeStructureCheck = PartialFunction[Type, Unit]

  val empty_class = name"empty_class".singleton
  val simple_trees = name"simple_trees".singleton
  val imports = name"imports".singleton
  val `simple_trees.nested` = simple_trees / name"nested"

  def containsSubtree(p: StructureCheck)(t: Tree): Boolean =
    t.walkTree(p.isDefinedAt)(_ || _, false)

  val isJavaLangObject: StructureCheck = {
    case Apply(
          Select(
            New(
              TypeWrapper(
                TypeRef(
                  PackageRef(QualifiedName(NameTags.QUALIFIED, SimpleName("java"), SimpleName("lang"))),
                  TypeName(SimpleName("Object"))
                )
              )
            ),
            SignedName(SimpleName("<init>"), _, _)
          ),
          List()
        ) =>
  }

  test("empty-class") {
    assert({
      {
        case PackageDef(
              s @ Symbol(SimpleName("empty_class")),
              List(
                ClassDef(
                  TypeName(SimpleName("EmptyClass")),
                  Template(
                    // default constructor: no type params, no arguments, empty body
                    DefDef(
                      SimpleName("<init>"),
                      Left(Nil) :: Nil,
                      TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Unit")))),
                      EmptyTree,
                      _
                    ),
                    // a single parent -- java.lang.Object
                    List(parent: Apply),
                    // self not specified => EmptyValDef
                    reusable.EmptyValDef,
                    // empty body
                    List()
                  ),
                  _
                )
              )
              // tree of package symbols is never set, because one package symbol corresponds to multiple trees
              // (defined in different files)
            ) if isJavaLangObject.isDefinedAt(parent) && s.tree.isEmpty =>
      }: StructureCheck
    }.isDefinedAt(clue(unpickle(empty_class / tname"EmptyClass"))))
  }

  test("nested-packages") {
    val tree = unpickle(`simple_trees.nested` / tname"InNestedPackage")

    val nestedPackages: StructureCheck = {
      case PackageDef(
            Symbol(SimpleName("simple_trees")),
            List(
              PackageDef(Symbol(QualifiedName(NameTags.QUALIFIED, SimpleName("simple_trees"), SimpleName("nested"))), _)
            )
          ) =>
    }

    assert(containsSubtree(nestedPackages)(clue(tree)))
  }

  test("qualified-nested-package") {
    val tree = unpickle(`simple_trees.nested` / tname"InQualifiedNestedPackage")

    val packageCheck: StructureCheck = {
      case PackageDef(Symbol(QualifiedName(NameTags.QUALIFIED, SimpleName("simple_trees"), SimpleName("nested"))), _) =>
    }

    assert(containsSubtree(packageCheck)(clue(tree)))
  }

  test("basic-import") {
    val importMatch: StructureCheck = {
      case Import(_, List(ImportSelector(Ident(SimpleName("A")), EmptyTree, EmptyTypeTree))) =>
    }
    assert(containsSubtree(clue(importMatch))(clue(unpickle(imports / tname"Import"))))
  }

  test("multiple-imports") {
    val importMatch: StructureCheck = {
      case Import(
            ReferencedPackage(SimpleName("imported_files")),
            List(
              ImportSelector(Ident(SimpleName("A")), EmptyTree, EmptyTypeTree),
              ImportSelector(Ident(SimpleName("B")), EmptyTree, EmptyTypeTree)
            )
          ) =>
    }
    assert(containsSubtree(importMatch)(clue(unpickle(imports / tname"MultipleImports"))))
  }

  test("renamed-import") {
    val importMatch: StructureCheck = {
      case Import(
            ReferencedPackage(SimpleName("imported_files")),
            List(ImportSelector(Ident(SimpleName("A")), Ident(SimpleName("ClassA")), EmptyTypeTree))
          ) =>
    }
    assert(containsSubtree(importMatch)(clue(unpickle(imports / tname"RenamedImport"))))
  }

  test("given-import") {
    val importMatch: StructureCheck = {
      // A given import selector has an empty name
      case Import(
            // TODO: SELECTtpt?
            Select(ReferencedPackage(SimpleName("imported_files")), SimpleName("Givens")),
            List(ImportSelector(Ident(nme.EmptyTermName), EmptyTree, EmptyTypeTree))
          ) =>
    }
    assert(containsSubtree(importMatch)(clue(unpickle(imports / tname"ImportGiven"))))
  }

  test("given-bounded-import") {
    val tree = unpickle(imports / tname"ImportGivenWithBound")
    val importMatch: StructureCheck = {
      // A given import selector has an empty name
      case Import(
            // TODO: SELECTtpt?
            Select(ReferencedPackage(SimpleName("imported_files")), SimpleName("Givens")),
            ImportSelector(Ident(nme.EmptyTermName), EmptyTree, TypeIdent(TypeName(SimpleName("A")))) :: Nil
          ) =>
    }
    assert(containsSubtree(importMatch)(clue(tree)))
  }

  test("export") {
    val tree = unpickle(simple_trees / tname"Export")
    val simpleExport: StructureCheck = {
      case Export(
            Select(This(Some(TypeIdent(TypeName(SimpleName("Export"))))), SimpleName("first")),
            ImportSelector(Ident(SimpleName("status")), EmptyTree, EmptyTypeTree) :: Nil
          ) =>
    }
    assert(containsSubtree(simpleExport)(clue(tree)))

    val omittedAndWildcardExport: StructureCheck = {
      case Export(
            Select(This(Some(TypeIdent(TypeName(SimpleName("Export"))))), SimpleName("second")),
            // An omitting selector is simply a rename to _
            ImportSelector(Ident(SimpleName("status")), Ident(nme.Wildcard), EmptyTypeTree) ::
            ImportSelector(Ident(nme.Wildcard), EmptyTree, EmptyTypeTree) :: Nil
          ) =>
    }
    assert(containsSubtree(omittedAndWildcardExport)(clue(tree)))

    val givenExport: StructureCheck = {
      case Export(
            Select(This(Some(TypeIdent(TypeName(SimpleName("Export"))))), SimpleName("givens")),
            // A given selector has an empty name
            ImportSelector(Ident(nme.EmptyTermName), EmptyTree, TypeIdent(TypeName(SimpleName("AnyRef")))) :: Nil
          ) =>
    }
    assert(containsSubtree(givenExport)(clue(tree)))
  }

  test("identity-method") {
    val identityMatch: StructureCheck = {
      case DefDef(
            SimpleName("id"),
            // no type params, one param -- x: Int
            List(Left(List(ValDef(SimpleName("x"), TypeIdent(TypeName(SimpleName("Int"))), EmptyTree, valSymbol)))),
            TypeIdent(TypeName(SimpleName("Int"))),
            Ident(SimpleName("x")),
            defSymbol
          ) if valSymbol.tree.exists(_.isInstanceOf[ValDef]) && defSymbol.tree.exists(_.isInstanceOf[DefDef]) =>
    }
    assert(containsSubtree(identityMatch)(clue(unpickle(simple_trees / tname"IdentityMethod"))))
  }

  test("multiple-parameter-lists") {
    val tree = unpickle(simple_trees / tname"MultipleParameterLists")
    val methodMatch: StructureCheck = {
      case DefDef(
            SimpleName("threeParameterLists"),
            List(
              Left(List(ValDef(SimpleName("x"), _, _, _))),
              Left(List(ValDef(SimpleName("y"), _, _, _), ValDef(SimpleName("z"), _, _, _))),
              Left(List(ValDef(SimpleName("last"), _, _, _)))
            ),
            _,
            _,
            _
          ) =>
    }
    assert(containsSubtree(methodMatch)(clue(tree)))
  }

  test("constants") {
    val tree = unpickle(simple_trees / tname"Constants")
    val unitConstMatch: StructureCheck = { case ValDef(SimpleName("unitVal"), _, Literal(Constant(())), _) =>
    }
    assert(containsSubtree(unitConstMatch)(clue(tree)))

    val falseConstMatch: StructureCheck = { case ValDef(SimpleName("falseVal"), _, Literal(Constant(false)), _) =>
    }
    assert(containsSubtree(falseConstMatch)(clue(tree)))

    val trueConstMatch: StructureCheck = { case ValDef(SimpleName("trueVal"), _, Literal(Constant(true)), _) =>
    }
    assert(containsSubtree(trueConstMatch)(clue(tree)))

    val byteConstMatch: StructureCheck = { case ValDef(SimpleName("byteVal"), _, Literal(Constant(1)), _) =>
    }
    assert(containsSubtree(byteConstMatch)(clue(tree)))

    val shortConstMatch: StructureCheck = { case ValDef(SimpleName("shortVal"), _, Literal(Constant(1)), _) =>
    }
    assert(containsSubtree(shortConstMatch)(clue(tree)))

    val charConstMatch: StructureCheck = { case ValDef(SimpleName("charVal"), _, Literal(Constant('a')), _) =>
    }
    assert(containsSubtree(charConstMatch)(clue(tree)))

    val intConstMatch: StructureCheck = { case ValDef(SimpleName("intVal"), _, Literal(Constant(1)), _) =>
    }
    assert(containsSubtree(intConstMatch)(clue(tree)))

    val longConstMatch: StructureCheck = { case ValDef(SimpleName("longVal"), _, Literal(Constant(1)), _) =>
    }
    assert(containsSubtree(longConstMatch)(clue(tree)))

    val floatConstMatch: StructureCheck = { case ValDef(SimpleName("floatVal"), _, Literal(Constant(1.1f)), _) =>
    }
    assert(containsSubtree(floatConstMatch)(clue(tree)))

    val doubleConstMatch: StructureCheck = { case ValDef(SimpleName("doubleVal"), _, Literal(Constant(1.1d)), _) =>
    }
    assert(containsSubtree(doubleConstMatch)(clue(tree)))

    val stringConstMatch: StructureCheck = { case ValDef(SimpleName("stringVal"), _, Literal(Constant("string")), _) =>
    }
    assert(containsSubtree(stringConstMatch)(clue(tree)))

    val nullConstMatch: StructureCheck = { case ValDef(SimpleName("nullVal"), _, Literal(Constant(null)), _) =>
    }
    assert(containsSubtree(nullConstMatch)(clue(tree)))
  }

  test("if") {
    val ifMatch: StructureCheck = {
      case If(
            Apply(Select(Ident(SimpleName("x")), SignedName(SimpleName("<"), _, _)), List(Literal(Constant(0)))),
            Select(Ident(SimpleName("x")), SimpleName("unary_-")),
            Ident(SimpleName("x"))
          ) =>
    }
    val tree = unpickle(simple_trees / tname"If")
    assert(containsSubtree(ifMatch)(clue(tree)))
  }

  test("block") {
    val blockMatch: StructureCheck = {
      case Block(
            List(
              ValDef(SimpleName("a"), _, Literal(Constant(1)), _),
              ValDef(SimpleName("b"), _, Literal(Constant(2)), _)
            ),
            Literal(Constant(()))
          ) =>
    }
    val tree = unpickle(simple_trees / tname"Block")
    assert(containsSubtree(blockMatch)(clue(tree)))
  }

  test("empty-infinite-while") {
    val whileMatch: StructureCheck = { case While(Literal(Constant(true)), Block(Nil, Literal(Constant(())))) =>
    }
    val tree = unpickle(simple_trees / tname"While")
    assert(containsSubtree(whileMatch)(clue(tree)))
  }

  test("match") {
    val tree = unpickle(simple_trees / tname"Match")

    val matchStructure: StructureCheck = {
      case Match(Ident(_), cases) if cases.length == 6 =>
    }
    assert(containsSubtree(matchStructure)(clue(tree)))

    val simpleGuard: StructureCheck = { case CaseDef(Literal(Constant(0)), EmptyTree, body: Block) =>
    }
    assert(containsSubtree(simpleGuard)(clue(tree)))

    val guardWithAlternatives: StructureCheck = {
      case CaseDef(
            Alternative(List(Literal(Constant(1)), Literal(Constant(-1)), Literal(Constant(2)))),
            EmptyTree,
            body: Block
          ) =>
    }
    assert(containsSubtree(guardWithAlternatives)(clue(tree)))

    val guardAndCondition: StructureCheck = {
      case CaseDef(
            Literal(Constant(7)),
            Apply(Select(Ident(SimpleName("x")), SignedName(SimpleName("=="), _, _)), Literal(Constant(7)) :: Nil),
            body: Block
          ) =>
    }
    assert(containsSubtree(guardAndCondition)(clue(tree)))

    val alternativesAndCondition: StructureCheck = {
      case CaseDef(
            Alternative(List(Literal(Constant(3)), Literal(Constant(4)), Literal(Constant(5)))),
            Apply(Select(Ident(SimpleName("x")), SignedName(SimpleName("<"), _, _)), Literal(Constant(5)) :: Nil),
            body: Block
          ) =>
    }
    assert(containsSubtree(alternativesAndCondition)(clue(tree)))

    val defaultWithCondition: StructureCheck = {
      case CaseDef(
            Ident(nme.Wildcard),
            Apply(
              Select(
                Apply(Select(Ident(SimpleName("x")), SignedName(SimpleName("%"), _, _)), Literal(Constant(2)) :: Nil),
                SignedName(SimpleName("=="), _, _)
              ),
              Literal(Constant(0)) :: Nil
            ),
            body: Block
          ) =>
    }
    assert(containsSubtree(defaultWithCondition)(clue(tree)))

    val default: StructureCheck = { case CaseDef(Ident(nme.Wildcard), EmptyTree, body: Block) =>
    }
    assert(containsSubtree(default)(clue(tree)))
  }

  test("match-case-class") {
    val tree = unpickle(simple_trees / tname"PatternMatchingOnCaseClass")

    val guardWithAlternatives: StructureCheck = {
      case CaseDef(
            Typed(
              Unapply(
                Select(Ident(SimpleName("FirstCase")), SignedName(SimpleName("unapply"), _, _)),
                Nil,
                List(Bind(SimpleName("x"), Ident(nme.Wildcard), bindSymbol))
              ),
              _
            ),
            EmptyTree,
            body: Block
          ) if bindSymbol.tree.exists(_.isInstanceOf[Bind]) =>
    }
    assert(containsSubtree(guardWithAlternatives)(clue(tree)))
  }

  test("assign") {
    val tree = unpickle(simple_trees / tname"Assign")
    val assignBlockMatch: StructureCheck = {
      case Block(
            List(
              ValDef(SimpleName("y"), tpt, Literal(Constant(0)), _),
              Assign(Ident(SimpleName("y")), Ident(SimpleName("x")))
            ),
            Ident(SimpleName("x"))
          ) =>
    }
    assert(containsSubtree(assignBlockMatch)(clue(tree)))
  }

  test("throw") {
    val tree = unpickle(simple_trees / tname"ThrowException")
    val throwMatch: StructureCheck = {
      case Throw(
            Apply(
              Select(
                New(TypeIdent(TypeName(SimpleName("NullPointerException")))),
                SignedName(SimpleName("<init>"), _, _)
              ),
              Nil
            )
          ) =>
    }
    assert(containsSubtree(throwMatch)(clue(tree)))
  }

  test("try-catch") {
    val tree = unpickle(simple_trees / tname"TryCatch")
    val tryMatch: StructureCheck = {
      case Try(
            _,
            CaseDef(Ident(nme.Wildcard), EmptyTree, Block(Nil, Literal(Constant(0)))) :: Nil,
            Block(Nil, Literal(Constant(())))
          ) =>
    }
    assert(containsSubtree(tryMatch)(clue(tree)))
  }

  test("singletonType") {
    val tree = unpickle(simple_trees / tname"SingletonType")
    val defDefWithSingleton: StructureCheck = {
      case DefDef(
            SimpleName("singletonReturnType"),
            List(Left(List(_))),
            SingletonTypeTree(Ident(SimpleName("x"))),
            Ident(SimpleName("x")),
            _
          ) =>
    }
    assert(containsSubtree(defDefWithSingleton)(clue(tree)))
  }

  test("defaultSelfType") {
    val tree = unpickle(simple_trees / tname"ClassWithSelf")
    val selfDefMatch: StructureCheck = {
      case ValDef(
            SimpleName("self"),
            TypeWrapper(TypeRef(PackageRef(SimpleName("simple_trees")), Symbol(TypeName(SimpleName("ClassWithSelf"))))),
            EmptyTree,
            NoSymbol
          ) =>
    }
    assert(containsSubtree(selfDefMatch)(clue(tree)))
  }

  test("selfType") {
    val tree = unpickle(simple_trees / tname"TraitWithSelf")
    val selfDefMatch: StructureCheck = {
      case ValDef(SimpleName("self"), TypeIdent(TypeName(SimpleName("ClassWithSelf"))), EmptyTree, NoSymbol) =>
    }
    assert(containsSubtree(selfDefMatch)(clue(tree)))
  }

  test("fields") {
    val tree = unpickle(simple_trees / tname"Field")

    val classFieldMatch: StructureCheck = {
      case ValDef(SimpleName("x"), TypeIdent(TypeName(SimpleName("Field"))), Literal(c), _) if c.tag == NullTag =>
    }
    assert(containsSubtree(classFieldMatch)(clue(tree)))

    val intFieldMatch: StructureCheck = {
      case ValDef(SimpleName("y"), TypeIdent(TypeName(SimpleName("Int"))), Literal(c), _)
          if c.value == 0 && c.tag == IntTag =>
    }
    assert(containsSubtree(intFieldMatch)(clue(tree)))
  }

  test("object") {
    val tree = unpickle(simple_trees / tname"ScalaObject")

    val selfDefMatch: StructureCheck = {
      case ValDef(nme.Wildcard, SingletonTypeTree(Ident(SimpleName("ScalaObject"))), EmptyTree, NoSymbol) =>
    }
    assert(containsSubtree(selfDefMatch)(clue(tree)))

    // check that the class constant from writeReplace is unpickled
    val classConstMatch: StructureCheck = {
      case Literal(c) if c.tag == ClazzTag =>
    }
    assert(containsSubtree(classConstMatch)(clue(tree)))
  }

  test("typed") {
    val tree = unpickle(simple_trees / tname"Typed")

    val typedMatch: StructureCheck = {
      case Typed(Literal(c), TypeIdent(TypeName(SimpleName("Int")))) if c.tag == IntTag && c.value == 1 =>
    }
    assert(containsSubtree(typedMatch)(clue(tree)))
  }

  test("repeated") {
    val tree = unpickle(simple_trees / tname"Repeated")

    val typedRepeated: StructureCheck = {
      case Typed(
            SeqLiteral(
              Literal(c1) :: Literal(c2) :: Literal(c3) :: Nil,
              TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int"))))
            ),
            TypeWrapper(
              AppliedType(
                TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("<repeated>"))),
                TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int"))) :: Nil
              )
            )
          ) =>
    }
    assert(containsSubtree(typedRepeated)(clue(tree)))
  }

  test("applied-type-annot") {
    val tree = unpickle(simple_trees / tname"AppliedTypeAnnotation")

    val valDefMatch: StructureCheck = {
      case ValDef(
            SimpleName("x"),
            AppliedTypeTree(TypeIdent(TypeName(SimpleName("Option"))), TypeIdent(TypeName(SimpleName("Int"))) :: Nil),
            Ident(SimpleName("None")),
            _
          ) =>
    }
    assert(containsSubtree(valDefMatch)(clue(tree)))
  }

  test("construct-inner-class") {
    val tree = unpickle(simple_trees / tname"InnerClass")

    val innerInstanceMatch: StructureCheck = {
      case ValDef(
            SimpleName("innerInstance"),
            // "Inner" inside THIS
            TypeWrapper(
              TypeRef(
                ThisType(TypeRef(PackageRef(SimpleName("simple_trees")), Symbol(TypeName(SimpleName("InnerClass"))))),
                Symbol(TypeName(SimpleName("Inner")))
              )
            ),
            Apply(Select(New(TypeIdent(TypeName(SimpleName("Inner")))), _), Nil),
            _
          ) =>
    }
    assert(containsSubtree(innerInstanceMatch)(clue(tree)))
  }

  test("type-application") {
    val tree = unpickle(simple_trees / tname"TypeApply")

    val applyMatch: StructureCheck = {
      case Apply(
            // apply[Int]
            TypeApply(
              Select(Ident(SimpleName("Seq")), SignedName(SimpleName("apply"), _, _)),
              TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int")))) :: Nil
            ),
            Typed(SeqLiteral(Literal(Constant(1)) :: Nil, _), _) :: Nil
          ) =>
    }
    assert(containsSubtree(applyMatch)(clue(tree)))
  }

  test("final") {
    val tree = unpickle(simple_trees / tname"Final")

    val constTypeMatch: StructureCheck = {
      case ValDef(SimpleName("Const"), TypeWrapper(ConstantType(Constant(1))), Literal(Constant(1)), _) =>
    }
    assert(containsSubtree(constTypeMatch)(clue(tree)))
  }

  test("var") {
    val tree = unpickle(simple_trees / tname"Var")

    // var = val with a setter
    val valDefMatch: StructureCheck = {
      case ValDef(
            SimpleName("x"),
            TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int")))),
            Literal(Constant(1)),
            symbol
          ) if symbol.tree.exists(_.isInstanceOf[ValDef]) =>
    }
    val setterMatch: StructureCheck = {
      case DefDef(
            SimpleName("x_="),
            Left((ValDef(SimpleName("x$1"), _, _, _): Matchable) :: Nil) :: Nil,
            TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Unit")))),
            Literal(Constant(())),
            _
          ) =>
    }
    assert(containsSubtree(valDefMatch)(clue(tree)))
    assert(containsSubtree(setterMatch)(clue(tree)))

    // x = 2
    val assignmentMatch: StructureCheck = {
      case Assign(Select(This(Some(TypeIdent(TypeName(SimpleName("Var"))))), SimpleName("x")), Literal(Constant(2))) =>
    }
    assert(containsSubtree(assignmentMatch)(clue(tree)))
  }

  test("constructor-with-parameters") {
    val tree = unpickle(simple_trees / tname"ConstructorWithParameters")
    val classWithParams: StructureCheck = {
      case Template(
            DefDef(
              SimpleName("<init>"),
              List(
                Left(
                  List(
                    ValDef(SimpleName("local"), _, _, _),
                    ValDef(SimpleName("theVal"), _, _, _),
                    ValDef(SimpleName("theVar"), _, _, _),
                    ValDef(SimpleName("privateVal"), _, _, _)
                  )
                )
              ),
              _,
              _,
              _
            ),
            jlObject :: Nil,
            _,
            // TODO: check the modifiers (private, local etc) once they are read
            // constructor parameters are members
            List(
              ValDef(SimpleName("local"), _, _, _),
              ValDef(SimpleName("theVal"), _, _, _),
              ValDef(SimpleName("theVar"), _, _, _),
              ValDef(SimpleName("privateVal"), _, _, _),
              // setter for theVar
              DefDef(SimpleName("theVar_="), List(Left(List(_))), _, _, _)
            )
          ) =>
    }
    assert(containsSubtree(classWithParams)(clue(tree)))
  }

  test("call-parent-constructor-with-defaults") {
    val tree = unpickle(simple_trees / tname"ChildCallsParentWithDefaultParameter")

    val blockParent: StructureCheck = {
      case ClassDef(
            TypeName(SimpleName("ChildCallsParentWithDefaultParameter")),
            Template(_, List(Block(_, _)), _, _),
            symbol
          ) if symbol.tree.exists(_.isInstanceOf[ClassDef]) =>
    }
    assert(containsSubtree(blockParent)(clue(tree)))
  }

  test("use-given") {
    val tree = unpickle(simple_trees / tname"UsingGiven")

    // given Int
    val givenDefinition: StructureCheck = {
      case ValDef(SimpleName("given_Int"), TypeIdent(TypeName(SimpleName("Int"))), _, _) =>
    }
    assert(containsSubtree(givenDefinition)(clue(tree)))

    // def useGiven(using Int)
    // useGiven
    val withGiven: StructureCheck = {
      case Apply(
            Select(This(Some(TypeIdent(TypeName(SimpleName("UsingGiven"))))), SignedName(SimpleName("useGiven"), _, _)),
            Select(This(Some(TypeIdent(TypeName(SimpleName("UsingGiven"))))), SimpleName("given_Int")) :: Nil
          ) =>
    }
    assert(containsSubtree(withGiven)(clue(tree)))

    // useGiven(using 0)
    val explicitUsing: StructureCheck = {
      case Apply(
            Select(This(Some(TypeIdent(TypeName(SimpleName("UsingGiven"))))), SignedName(SimpleName("useGiven"), _, _)),
            Literal(Constant(0)) :: Nil
          ) =>
    }
    assert(containsSubtree(explicitUsing)(clue(tree)))
  }

  test("trait-with-parameter") {
    val tree = unpickle(simple_trees / tname"TraitWithParameter")

    val traitMatch: StructureCheck = {
      case Template(
            DefDef(
              SimpleName("<init>"),
              List(Left((ValDef(SimpleName("param"), _, _, _): Matchable) :: Nil)),
              _,
              EmptyTree,
              _
            ),
            TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Object")))) :: Nil,
            _,
            ValDef(SimpleName("param"), _, _, _) :: Nil
          ) =>
    }

  }

  test("extend-trait") {
    val tree = unpickle(simple_trees / tname"ExtendsTrait")

    val classMatch: StructureCheck = {
      case Template(
            _,
            List(jlObject: Apply, TypeIdent(TypeName(SimpleName("AbstractTrait")))),
            _,
            // TODO: check the OVERRIDE modifer once modifiers are read
            DefDef(SimpleName("abstractMethod"), _, _, _, _) :: Nil
          ) if isJavaLangObject.isDefinedAt(jlObject) =>
    }
    assert(containsSubtree(classMatch)(clue(tree)))
  }

  test("lambda") {
    val tree = unpickle(simple_trees / tname"Function")

    val functionLambdaMatch: StructureCheck = {
      case ValDef(
            SimpleName("functionLambda"),
            _,
            Block(
              List(DefDef(SimpleName("$anonfun"), List(Left(List(ValDef(SimpleName("x"), _, _, _)))), _, _, _)),
              // a lambda is simply a wrapper around a DefDef, defined in the same block. Its type is a function type,
              // therefore not specified (left as EmptyTree)
              Lambda(Ident(SimpleName("$anonfun")), EmptyTypeTree)
            ),
            _
          ) =>
    }
    assert(containsSubtree(functionLambdaMatch)(clue(tree)))

    val SAMLambdaMatch: StructureCheck = {
      case ValDef(
            SimpleName("samLambda"),
            _,
            Block(
              List(DefDef(SimpleName("$anonfun"), List(Left(Nil)), _, _, _)),
              // the lambda's type is not just a function type, therefore specified
              Lambda(
                Ident(SimpleName("$anonfun")),
                TypeWrapper(
                  TypeRef(
                    PackageRef(QualifiedName(NameTags.QUALIFIED, SimpleName("java"), SimpleName("lang"))),
                    TypeName(SimpleName("Runnable"))
                  )
                )
              )
            ),
            _
          ) =>
    }
    assert(containsSubtree(SAMLambdaMatch)(clue(tree)))
  }

  test("eta-expansion") {
    val tree = unpickle(simple_trees / tname"EtaExpansion")

    /*
    takesFunction(intMethod)
    the compiler generates a function which simply calls intMethod;
    this function is passed as the argument to takesFunction
     */
    val applicationMatch: StructureCheck = {
      case Apply(
            Select(
              This(Some(TypeIdent(TypeName(SimpleName("EtaExpansion"))))),
              SignedName(SimpleName("takesFunction"), _, _)
            ),
            Block(
              List(
                DefDef(
                  SimpleName("$anonfun"),
                  Left(List(ValDef(SimpleName("x"), _, _, _))) :: Nil,
                  _,
                  Apply(
                    Select(
                      This(Some(TypeIdent(TypeName(SimpleName("EtaExpansion"))))),
                      SignedName(SimpleName("intMethod"), _, _)
                    ),
                    List(Ident(SimpleName("x")))
                  ),
                  _
                )
              ),
              Lambda(Ident(SimpleName("$anonfun")), EmptyTypeTree)
            ) :: Nil
          ) =>
    }
    assert(containsSubtree(applicationMatch)(clue(tree)))
  }

  test("partial-application") {
    val tree = unpickle(simple_trees / tname"PartialApplication")

    // Partial application under the hood is defining a function which takes the remaining parameters
    // and calls the original function with fixed + remaining parameters
    val applicationMatch: StructureCheck = {
      case DefDef(
            SimpleName("partiallyApplied"),
            Nil,
            _,
            Block(
              List(
                DefDef(
                  SimpleName("$anonfun"),
                  Left((ValDef(SimpleName("second"), _, _, _): Matchable) :: Nil) :: Nil,
                  _,
                  Apply(
                    Apply(
                      Select(
                        This(Some(TypeIdent(TypeName(SimpleName("PartialApplication"))))),
                        SignedName(SimpleName("withManyParams"), _, _)
                      ),
                      Literal(Constant(0)) :: Nil
                    ),
                    Ident(SimpleName("second")) :: Nil
                  ),
                  _
                )
              ),
              Lambda(Ident(SimpleName("$anonfun")), EmptyTypeTree)
            ),
            _
          ) =>
    }
    assert(containsSubtree(applicationMatch)(clue(tree)))
  }

  test("partial-function") {
    val tree = unpickle(simple_trees / tname"WithPartialFunction")

    val partialFunction: StructureCheck = {
      case DefDef(
            SimpleName("$anonfun"),
            Left(List(ValDef(SimpleName("x$1"), _, EmptyTree, _))) :: Nil,
            _,
            // match x$1 with type x$1
            Match(
              Typed(
                Ident(SimpleName("x$1")),
                TypeWrapper(
                  AnnotatedType(
                    TermRef(NoPrefix, Symbol(SimpleName("x$1"))),
                    New(TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("unchecked")))))
                  )
                )
              ),
              cases
            ),
            _
          ) =>
    }
    assert(containsSubtree(partialFunction)(clue(tree)))
  }

  test("named-argument") {
    val tree = unpickle(simple_trees / tname"NamedArgument")

    val withNamedArgumentApplication: StructureCheck = {
      case Apply(
            Select(
              This(Some(TypeIdent(TypeName(SimpleName("NamedArgument"))))),
              SignedName(SimpleName("withNamed"), _, _)
            ),
            List(Literal(Constant(0)), NamedArg(SimpleName("second"), Literal(Constant(1))))
          ) =>
    }
    assert(containsSubtree(withNamedArgumentApplication)(clue(tree)))
  }

  test("return") {
    val tree = unpickle(simple_trees / tname"Return")

    val returnMatch: StructureCheck = { case Return(Literal(Constant(1)), Ident(SimpleName("withReturn"))) =>
    }
    assert(containsSubtree(returnMatch)(clue(tree)))
  }

  test("super") {
    val tree = unpickle(simple_trees / tname"Super")

    val superMatch: StructureCheck = { case Super(This(None), None) =>
    }
    assert(containsSubtree(superMatch)(clue(tree)))

    val mixinSuper: StructureCheck = { case Super(This(None), Some(TypeIdent(TypeName(SimpleName("Base"))))) =>
    }
    assert(containsSubtree(mixinSuper)(clue(tree)))
  }

  test("type-member") {
    val tree = unpickle(simple_trees / tname"TypeMember")

    // simple type member
    val typeMember: StructureCheck = {
      case TypeMember(TypeName(SimpleName("TypeMember")), TypeIdent(TypeName(SimpleName("Int"))), symbol)
          if symbol.tree.exists(_.isInstanceOf[TypeMember]) =>
    }
    assert(containsSubtree(typeMember)(clue(tree)))

    // abstract without user-specified bounds, therefore default bounds are generated
    val abstractTypeMember: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("AbstractType")),
            BoundedTypeTree(
              TypeBoundsTree(
                TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
              ),
              EmptyTypeTree
            ),
            _
          ) =>
    }
    assert(containsSubtree(abstractTypeMember)(clue(tree)))

    // abstract with explicit bounds
    val abstractWithBounds: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("AbstractWithBounds")),
            BoundedTypeTree(
              TypeBoundsTree(TypeIdent(TypeName(SimpleName("Null"))), TypeIdent(TypeName(SimpleName("AnyRef")))),
              EmptyTypeTree
            ),
            _
          ) =>
    }
    assert(containsSubtree(abstractWithBounds)(clue(tree)))

    // opaque
    val opaqueTypeMember: StructureCheck = {
      case TypeMember(TypeName(SimpleName("Opaque")), TypeIdent(TypeName(SimpleName("Int"))), _) =>
    }
    assert(containsSubtree(opaqueTypeMember)(clue(tree)))

    // bounded opaque
    val opaqueWithBounds: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("OpaqueWithBounds")),
            BoundedTypeTree(
              TypeBoundsTree(TypeIdent(TypeName(SimpleName("Null"))), TypeIdent(TypeName(SimpleName("AnyRef")))),
              TypeIdent(TypeName(SimpleName("Null")))
            ),
            _
          ) =>
    }
    assert(containsSubtree(opaqueWithBounds)(clue(tree)))
  }

  test("generic-class") {
    val tree = unpickle(simple_trees / tname"GenericClass")

    /*
    The class and its constructor have the same type bounds for the type parameter,
    but the constructor's are attached to the type parameter declaration in the code,
    and the class's are just typebounds (no associated code location), hence the difference in structures
     */
    val genericClass: StructureCheck = {
      case ClassDef(
            TypeName(SimpleName("GenericClass")),
            Template(
              DefDef(
                SimpleName("<init>"),
                List(
                  Right(
                    List(
                      TypeParam(
                        TypeName(SimpleName("T")),
                        TypeBoundsTree(
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                        ),
                        firstTypeParamSymbol
                      )
                    )
                  ),
                  Left(Nil)
                ),
                _,
                _,
                _
              ),
              _,
              _,
              TypeParam(
                TypeName(SimpleName("T")),
                RealTypeBounds(
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing"))),
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any")))
                ),
                secondTypeParamSymbol
              ) :: _
            ),
            classSymbol
          )
          if classSymbol.tree.exists(_.isInstanceOf[ClassDef])
            && firstTypeParamSymbol.tree.exists(_.isInstanceOf[TypeParam])
            && secondTypeParamSymbol.tree.exists(_.isInstanceOf[TypeParam]) =>
    }
    assert(containsSubtree(genericClass)(clue(tree)))
  }

  test("generic-method") {
    val tree = unpickle(simple_trees / tname"GenericMethod")
    val genericMethod: StructureCheck = {
      case DefDef(
            SimpleName("usesTypeParam"),
            List(
              Right(
                List(
                  TypeParam(
                    TypeName(SimpleName("T")),
                    TypeBoundsTree(
                      TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                      TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                    ),
                    _
                  )
                )
              ),
              Left(Nil)
            ),
            AppliedTypeTree(TypeIdent(TypeName(SimpleName("Option"))), TypeIdent(TypeName(SimpleName("T"))) :: Nil),
            _,
            _
          ) =>
    }
    assert(containsSubtree(genericMethod)(clue(tree)))
  }

  test("generic-extension") {
    val tree = unpickle(simple_trees / tname"GenericExtension$$package")

    val extensionCheck: StructureCheck = {
      case DefDef(
            SimpleName("genericExtension"),
            List(
              Left(List(ValDef(SimpleName("i"), TypeIdent(TypeName(SimpleName("Int"))), EmptyTree, _))),
              Right(
                List(
                  TypeParam(
                    TypeName(SimpleName("T")),
                    TypeBoundsTree(
                      TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                      TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                    ),
                    _
                  )
                )
              ),
              Left(List(ValDef(SimpleName("genericArg"), TypeIdent(TypeName(SimpleName("T"))), EmptyTree, _)))
            ),
            _,
            _,
            _
          ) =>
    }
    assert(containsSubtree(extensionCheck)(clue(tree)))
  }

  test("class-type-bounds") {
    val tree = unpickle(simple_trees / tname"TypeBoundsOnClass")
    val genericClass: StructureCheck = {
      case ClassDef(
            TypeName(SimpleName("TypeBoundsOnClass")),
            Template(
              DefDef(
                SimpleName("<init>"),
                List(
                  Right(
                    List(
                      TypeParam(
                        TypeName(SimpleName("T")),
                        TypeBoundsTree(
                          TypeIdent(TypeName(SimpleName("Null"))),
                          TypeIdent(TypeName(SimpleName("AnyRef")))
                        ),
                        _
                      )
                    )
                  ),
                  Left(Nil)
                ),
                _,
                _,
                _
              ),
              _,
              _,
              TypeParam(
                TypeName(SimpleName("T")),
                RealTypeBounds(
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Null"))),
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("AnyRef")))
                ),
                _
              ) :: _
            ),
            symbol
          ) if symbol.tree.exists(_.isInstanceOf[ClassDef]) =>
    }
    assert(containsSubtree(genericClass)(clue(tree)))
  }

  test("shared-type-bounds") {
    // The type bounds of the class and its inner class are shared in the TASTy serializations.
    // This test checks that such shared type bounds are read correctly.
    val tree = unpickle(simple_trees / tname"GenericClassWithNestedGeneric")

    val genericClass: StructureCheck = {
      case ClassDef(
            TypeName(SimpleName("GenericClassWithNestedGeneric")),
            Template(
              DefDef(
                SimpleName("<init>"),
                List(
                  Right(
                    List(
                      TypeParam(
                        TypeName(SimpleName("T")),
                        TypeBoundsTree(
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                        ),
                        _
                      )
                    )
                  ),
                  Left(Nil)
                ),
                _,
                _,
                _
              ),
              _,
              _,
              TypeParam(
                TypeName(SimpleName("T")),
                RealTypeBounds(
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing"))),
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any")))
                ),
                _
              ) :: ClassDef(TypeName(SimpleName("NestedGeneric")), _, innerSymbol) :: _
            ),
            outerSymbol
          ) if outerSymbol.tree.exists(_.isInstanceOf[ClassDef]) && innerSymbol.tree.exists(_.isInstanceOf[ClassDef]) =>
    }
    assert(containsSubtree(genericClass)(clue(tree)))

    val nestedClass: StructureCheck = {
      case ClassDef(
            TypeName(SimpleName("NestedGeneric")),
            Template(
              DefDef(
                SimpleName("<init>"),
                List(
                  Right(
                    List(
                      TypeParam(
                        TypeName(SimpleName("U")),
                        TypeBoundsTree(
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                        ),
                        _
                      )
                    )
                  ),
                  Left(Nil)
                ),
                _,
                _,
                _
              ),
              _,
              _,
              TypeParam(
                TypeName(SimpleName("U")),
                RealTypeBounds(
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing"))),
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any")))
                ),
                _
              ) :: _
            ),
            symbol
          ) if symbol.tree.exists(_.isInstanceOf[ClassDef]) =>
    }
    assert(containsSubtree(nestedClass)(clue(tree)))
  }

  test("inline-method") {
    val tree = unpickle(simple_trees / tname"InlinedCall")

    val inlined: StructureCheck = {
      case Inlined(
            // 0 + HasInlinedMethod_this.externalVal
            Apply(
              Select(Inlined(Literal(Constant(0)), EmptyTypeIdent, Nil), SignedName(SimpleName("+"), _, _)),
              Select(
                Inlined(Ident(SimpleName("HasInlinedMethod_this")), EmptyTypeIdent, Nil),
                SimpleName("externalVal")
              ) :: Nil
            ),
            // the _toplevel_ class, method inside which is inlined
            TypeIdent(TypeName(SimpleName("HasInlinedMethod"))),
            ValDef(
              SimpleName("HasInlinedMethod_this"),
              _,
              Select(This(Some(TypeIdent(TypeName(SimpleName("InlinedCall"))))), SimpleName("withInlineMethod")),
              _
            ) :: Nil
          ) =>
    }
    assert(containsSubtree(inlined)(clue(tree)))
  }

  test("select-tpt") {
    val tree = unpickle(simple_trees / tname"SelectType")

    val selectTpt: StructureCheck = {
      case ValDef(
            SimpleName("random"),
            TypeWrapper(
              TypeRef(
                PackageRef(QualifiedName(NameTags.QUALIFIED, SimpleName("scala"), SimpleName("util"))),
                TypeName(SimpleName("Random"))
              )
            ),
            Apply(
              // select scala.util.Random
              Select(
                New(
                  SelectTypeTree(
                    TypeWrapper(PackageRef(QualifiedName(NameTags.QUALIFIED, SimpleName("scala"), SimpleName("util")))),
                    TypeName(SimpleName("Random"))
                  )
                ),
                SignedName(SimpleName("<init>"), _, _)
              ),
              Nil
            ),
            _
          ) =>
    }
    assert(containsSubtree(selectTpt)(clue(tree)))
  }

  test("by-name-parameter") {
    val tree = unpickle(simple_trees / tname"ByNameParameter")

    val byName: StructureCheck = {
      case DefDef(
            SimpleName("withByName"),
            List(
              Left(List(ValDef(SimpleName("x"), ByNameTypeTree(TypeIdent(TypeName(SimpleName("Int")))), EmptyTree, _)))
            ),
            _,
            _,
            _
          ) =>
    }
    assert(containsSubtree(byName)(clue(tree)))
  }

  test("by-name-type") {
    val tree = unpickle(simple_trees / tname"ClassWithByNameParameter")

    val byName: StructureCheck = {
      case ValDef(
            SimpleName("byNameParam"),
            TypeWrapper(ExprType(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int"))))),
            EmptyTree,
            _
          ) =>
    }
    assert(containsSubtree(byName)(clue(tree)))
  }

  test("union-type") {
    val tree = unpickle(simple_trees / tname"UnionType")

    val unionTypeMethod: StructureCheck = {
      case DefDef(
            SimpleName("argWithOrType"),
            // Int | String = | [Int, String]
            List(
              Left(
                List(
                  ValDef(
                    SimpleName("x"),
                    AppliedTypeTree(
                      TypeIdent(TypeName(SimpleName("|"))),
                      List(TypeIdent(TypeName(SimpleName("Int"))), TypeIdent(TypeName(SimpleName("String"))))
                    ),
                    EmptyTree,
                    _
                  )
                )
              )
            ),
            TypeWrapper(
              OrType(
                TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Int"))),
                TypeRef(TermRef(PackageRef(SimpleName("scala")), SimpleName("Predef")), TypeName(SimpleName("String")))
              )
            ),
            _,
            _
          ) =>
    }
    assert(containsSubtree(unionTypeMethod)(clue(tree)))
  }

  test("intersection-type") {
    val tree = unpickle(simple_trees / tname"IntersectionType")

    val intersectionTypeMethod: StructureCheck = {
      case DefDef(
            SimpleName("argWithAndType"),
            List(
              Left(
                List(
                  ValDef(
                    SimpleName("x"),
                    // IntersectionType & UnionType = & [IntersectionType, UnionType]
                    AppliedTypeTree(
                      TypeIdent(TypeName(SimpleName("&"))),
                      List(
                        TypeIdent(TypeName(SimpleName("IntersectionType"))),
                        TypeIdent(TypeName(SimpleName("UnionType")))
                      )
                    ),
                    EmptyTree,
                    _
                  )
                )
              )
            ),
            TypeWrapper(
              AndType(
                TypeRef(PackageRef(SimpleName("simple_trees")), Symbol(TypeName(SimpleName("IntersectionType")))),
                TypeRef(PackageRef(SimpleName("simple_trees")), TypeName(SimpleName("UnionType")))
              )
            ),
            _,
            _
          ) =>
    }
    assert(containsSubtree(intersectionTypeMethod)(clue(tree)))
  }

  test("type-lambda") {
    val tree = unpickle(simple_trees / tname"TypeLambda")

    val lambdaTpt: StructureCheck = {
      // TL: [X] =>> List[X]
      case TypeMember(
            TypeName(SimpleName("TL")),
            TypeLambdaTree(
              // [X]
              TypeParam(
                TypeName(SimpleName("X")),
                TypeBoundsTree(
                  TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                  TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                ),
                _
              ) :: Nil,
              // List[X]
              AppliedTypeTree(TypeIdent(TypeName(SimpleName("List"))), TypeIdent(TypeName(SimpleName("X"))) :: Nil)
            ),
            _
          ) =>
    }

    assert(containsSubtree(lambdaTpt)(clue(tree)))
  }

  test("type-lambda-type") {
    val tree = unpickle(simple_trees / tname"HigherKinded")

    val typeLambdaResultIsAny: TypeStructureCheck = {
      case TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))) =>
    }

    // A[_], i.e. A >: Nothing <: [X] =>> Any
    val typeLambda: StructureCheck = {
      case TypeParam(
            TypeName(SimpleName("A")),
            RealTypeBounds(
              nothing,
              tl @ TypeLambda(
                TypeParam(TypeName(UniqueName("_$", nme.EmptyTermName, 1)), RealTypeBounds(nothing2, any), _) :: Nil
              )
            ),
            _
          ) if typeLambdaResultIsAny.isDefinedAt(tl.resultType) =>
    }
    assert(containsSubtree(typeLambda)(clue(tree)))
  }

  object TyParamRef:
    def unapply(t: TypeParamRef): Some[Name] = Some(t.paramName)

  test("type-lambda-type-result-depends-on-param") {
    val tree = unpickle(simple_trees / tname"HigherKindedWithParam")

    // Type lambda result is List[X]
    val typeLambdaResultIsListOf: TypeStructureCheck = {
      case AppliedType(
            TypeRef(PackageRef(_), TypeName(SimpleName("List"))),
            TyParamRef(TypeName(SimpleName("X"))) :: Nil
          ) =>
    }

    // A[X] <: List[X], i.e. A >: Nothing <: [X] =>> List[X]
    val typeLambda: StructureCheck = {
      case TypeParam(
            TypeName(SimpleName("A")),
            RealTypeBounds(
              nothing,
              tl @ TypeLambda(TypeParam(TypeName(SimpleName("X")), RealTypeBounds(nothing2, any), _) :: Nil)
            ),
            _
          ) if typeLambdaResultIsListOf.isDefinedAt(tl.resultType) =>
    }
    assert(containsSubtree(typeLambda)(clue(tree)))
  }

  test("varags-annotated-type") {
    val tree = unpickle(simple_trees / tname"VarargFunction")

    val varargMatch: StructureCheck = {
      case DefDef(
            SimpleName("takesVarargs"),
            List(
              Left(
                List(
                  ValDef(
                    SimpleName("xs"),
                    AnnotatedTypeTree(
                      // Int* ==> Seq[Int]
                      AppliedTypeTree(
                        TypeWrapper(TypeRef(PackageRef(_), TypeName(SimpleName("Seq")))),
                        TypeIdent(TypeName(SimpleName("Int"))) :: Nil
                      ),
                      Apply(
                        Select(
                          New(TypeWrapper(TypeRef(PackageRef(_), TypeName(SimpleName("Repeated"))))),
                          SignedName(SimpleName("<init>"), _, _)
                        ),
                        Nil
                      )
                    ),
                    EmptyTree,
                    _
                  ): Matchable
                )
              )
            ),
            _,
            _,
            _
          ) =>
    }
    assert(containsSubtree(varargMatch)(clue(tree)))
  }

  test("refined-type-tree") {
    val tree = unpickle(simple_trees / tname"RefinedTypeTree")

    val refinedTpt: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("Refined")),
            // TypeMember { type AbstractType = Int }
            RefinedTypeTree(
              TypeIdent(TypeName(SimpleName("TypeMember"))),
              TypeMember(TypeName(SimpleName("AbstractType")), TypeIdent(TypeName(SimpleName("Int"))), _) :: Nil
            ),
            _
          ) =>
    }
    assert(containsSubtree(refinedTpt)(clue(tree)))
  }

  test("refined-type") {
    val tree = unpickle(simple_trees / tname"RefinedType")

    val refinedType: StructureCheck = {
      case Typed(
            expr,
            TypeWrapper(
              RefinedType(
                RefinedType(
                  TypeRef(PackageRef(SimpleName("simple_trees")), TypeName(SimpleName("TypeMember"))),
                  TypeName(SimpleName("AbstractType")),
                  TypeAlias(alias)
                ),
                TypeName(SimpleName("AbstractWithBounds")),
                TypeAlias(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Null"))))
              )
            )
          ) =>
    }
    assert(containsSubtree(refinedType)(clue(tree)))
  }

  test("match-type") {
    val tree = unpickle(simple_trees / tname"MatchType")

    val matchTpt: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("MT")),
            TypeLambdaTree(
              List(
                TypeParam(
                  TypeName(SimpleName("X")),
                  TypeBoundsTree(
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                  ),
                  _
                )
              ),
              MatchTypeTree(
                // No bound on the match result
                EmptyTypeTree,
                TypeIdent(TypeName(SimpleName("X"))),
                List(TypeCaseDef(TypeIdent(TypeName(SimpleName("Int"))), TypeIdent(TypeName(SimpleName("String")))))
              )
            ),
            _
          ) =>
    }
    assert(containsSubtree(matchTpt)(clue(tree)))

    val matchWithBound: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("MTWithBound")),
            TypeLambdaTree(
              List(
                TypeParam(
                  TypeName(SimpleName("X")),
                  TypeBoundsTree(
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                  ),
                  _
                )
              ),
              MatchTypeTree(
                TypeIdent(TypeName(SimpleName("Nothing"))),
                TypeIdent(TypeName(SimpleName("X"))),
                List(TypeCaseDef(TypeIdent(TypeName(SimpleName("Int"))), TypeIdent(TypeName(SimpleName("Nothing")))))
              )
            ),
            _
          ) =>
    }
    assert(containsSubtree(matchWithBound)(clue(tree)))

    val matchWithWildcard: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("MTWithWildcard")),
            TypeLambdaTree(
              List(
                TypeParam(
                  TypeName(SimpleName("X")),
                  TypeBoundsTree(
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                  ),
                  _
                )
              ),
              MatchTypeTree(
                // No bound on the match result
                EmptyTypeTree,
                TypeIdent(TypeName(SimpleName("X"))),
                List(
                  TypeCaseDef(
                    TypeTreeBind(
                      TypeName(UniqueName("_$", nme.EmptySimpleName, 1)),
                      BoundedTypeTree(
                        TypeBoundsTree(
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                          TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                        ),
                        EmptyTypeTree
                      ),
                      _
                    ),
                    TypeIdent(TypeName(SimpleName("Int")))
                  )
                )
              )
            ),
            _
          ) =>
    }
    assert(containsSubtree(matchWithWildcard)(clue(tree)))

    val matchWithBind: StructureCheck = {
      case TypeMember(
            TypeName(SimpleName("MTWithBind")),
            TypeLambdaTree(
              List(
                TypeParam(
                  TypeName(SimpleName("X")),
                  TypeBoundsTree(
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                    TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
                  ),
                  _
                )
              ),
              MatchTypeTree(
                // No bound on the match result
                EmptyTypeTree,
                TypeIdent(TypeName(SimpleName("X"))),
                List(
                  TypeCaseDef(
                    AppliedTypeTree(
                      TypeIdent(TypeName(SimpleName("List"))),
                      TypeTreeBind(TypeName(SimpleName("t")), NamedTypeBoundsTree(TypeName(nme.Wildcard), _), _) :: Nil
                    ),
                    TypeIdent(TypeName(SimpleName("t")))
                  )
                )
              )
            ),
            _
          ) =>
    }
    assert(containsSubtree(matchWithBind)(clue(tree)))
  }

  test("package-type-ref") {
    val tree = unpickle(tname"toplevelEmptyPackage$$package".singleton)

    // Empty package (the path to the toplevel$package[ModuleClass]) is a THIS of a TYPEREFpkg as opposed to
    // non-empty package, which is simply TERMREFpkg. Therefore, reading the type of the package object reads TYPEREFpkg.
    val packageVal: StructureCheck = {
      case ValDef(
            SimpleName("toplevelEmptyPackage$package"),
            TypeIdent(TypeName(SuffixedName(NameTags.OBJECTCLASS, SimpleName("toplevelEmptyPackage$package")))),
            _,
            _
          ) =>
    }
    assert(containsSubtree(packageVal)(clue(tree)))
  }

  test("wildcard-type-application") {
    val tree = unpickle(simple_trees / tname"WildcardTypeApplication")

    // class parameter as a val
    val appliedTypeToTypeBounds: StructureCheck = {
      case ValDef(
            SimpleName("anyList"),
            TypeWrapper(
              AppliedType(
                TypeRef(PackageRef(_), TypeName(SimpleName("List"))),
                RealTypeBounds(
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing"))),
                  TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any")))
                ) :: Nil
              )
            ),
            EmptyTree,
            _
          ) =>
    }
    assert(containsSubtree(appliedTypeToTypeBounds)(clue(tree)))

    // class parameter as a constructor parameter
    val appliedTypeToTypeBoundsTpt: StructureCheck = {
      case ValDef(
            SimpleName("anyList"),
            AppliedTypeTree(
              TypeIdent(TypeName(SimpleName("List"))),
              TypeBoundsTree(
                TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing")))),
                TypeWrapper(TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Any"))))
              ) :: Nil
            ),
            EmptyTree,
            _
          ) =>
    }
    assert(containsSubtree(appliedTypeToTypeBoundsTpt)(clue(tree)))

    // extends GenericWithTypeBound[_]
    val wildcardParent: StructureCheck = {
      case New(
            AppliedTypeTree(
              TypeIdent(TypeName(SimpleName("GenericWithTypeBound"))),
              RealTypeBounds(
                TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("Nothing"))),
                TypeRef(PackageRef(SimpleName("scala")), TypeName(SimpleName("AnyKind")))
              ) :: Nil
            )
          ) =>
    }
    assert(containsSubtree(wildcardParent)(clue(tree)))
  }

  test("qual-this-type") {
    val tree = unpickle(simple_trees / tname"QualThisType")

    val newInner: StructureCheck = {
      case New(
            SelectTypeTree(
              TypeWrapper(
                ThisType(TypeRef(PackageRef(SimpleName("simple_trees")), Symbol(TypeName(SimpleName("QualThisType")))))
              ),
              TypeName(SimpleName("Inner"))
            )
          ) =>
    }
    assert(containsSubtree(newInner)(clue(tree)))
  }

  test("shared-package-reference") {
    val tree = unpickle(simple_trees / tname"SharedPackageReference$$package")

    // TODO: once references are created, check correctness
  }

  test("typerefin") {
    val tree = unpickle(simple_trees / tname"TypeRefIn")

    val typerefCheck: StructureCheck = {
      case TypeApply(
            Select(qualifier, SignedName(SimpleName("withArray"), _, _)),
            // TODO: check the namespace ("in") once its taken into account
            TypeWrapper(TypeRef(TermRef(NoPrefix, Symbol(SimpleName("arr"))), TypeName(SimpleName("T")))) :: Nil
          ) =>
    }
    assert(containsSubtree(typerefCheck)(clue(tree)))
  }
}
