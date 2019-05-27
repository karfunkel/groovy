/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen.asm.sc;

import groovy.lang.Tuple;
import groovy.lang.Tuple2;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodReferenceExpression;
import org.codehaus.groovy.ast.tools.GeneralUtils;
import org.codehaus.groovy.ast.tools.ParameterUtils;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.codehaus.groovy.classgen.asm.MethodReferenceExpressionWriter;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.runtime.ArrayTypeUtils;
import org.codehaus.groovy.syntax.RuntimeParserException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.block;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.returnS;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.filterMethodsByVisibility;
import static org.codehaus.groovy.transform.stc.StaticTypesMarker.CLOSURE_ARGUMENTS;

/**
 * Writer responsible for generating method reference in statically compiled mode.
 *
 * @since 3.0.0
 */
public class StaticTypesMethodReferenceExpressionWriter extends MethodReferenceExpressionWriter implements AbstractFunctionalInterfaceWriter {
    private static final String METHODREF_EXPR_INSTANCE = "__METHODREF_EXPR_INSTANCE";

    public StaticTypesMethodReferenceExpressionWriter(WriterController controller) {
        super(controller);
    }

    @Override
    public void writeMethodReferenceExpression(MethodReferenceExpression methodReferenceExpression) {
        ClassNode functionalInterfaceType = getFunctionalInterfaceType(methodReferenceExpression);
        if (null == functionalInterfaceType) {
            // if the parameter type failed to be inferred, generate the default bytecode, which is actually a method closure
            super.writeMethodReferenceExpression(methodReferenceExpression);
            return;
        }

        ClassNode redirect = functionalInterfaceType.redirect();
        if (!ClassHelper.isFunctionalInterface(redirect)) {
            // if the parameter type is not real FunctionalInterface, generate the default bytecode, which is actually a method closure
            super.writeMethodReferenceExpression(methodReferenceExpression);
            return;
        }

        MethodNode abstractMethodNode = ClassHelper.findSAM(redirect);

        String abstractMethodDesc = createMethodDescriptor(abstractMethodNode);

        ClassNode classNode = controller.getClassNode();
        boolean isInterface = classNode.isInterface();

        Expression typeOrTargetRef = methodReferenceExpression.getExpression();
        ClassNode typeOrTargetRefType = typeOrTargetRef.getType();
        String methodRefName = methodReferenceExpression.getMethodName().getText();

        ClassNode[] methodReferenceParamTypes = methodReferenceExpression.getNodeMetaData(CLOSURE_ARGUMENTS);
        Parameter[] parametersWithExactType = createParametersWithExactType(abstractMethodNode, methodReferenceParamTypes);

        boolean isConstructorReference = isConstructorReference(methodRefName);
        if (isConstructorReference) {
            methodRefName = createSyntheticMethodForConstructorReference();
            addSyntheticMethodForConstructorReference(methodRefName, typeOrTargetRefType, parametersWithExactType);
        }

        // TODO move the `findMethodRefMethod` and checking to `StaticTypeCheckingVisitor`
        MethodNode methodRefMethod = findMethodRefMethod(methodRefName, parametersWithExactType, typeOrTargetRef, isConstructorReference);

        if (null == methodRefMethod) {
            throw new RuntimeParserException("Failed to find the expected method["
                    + methodRefName + "("
                    + Arrays.stream(parametersWithExactType)
                            .map(e -> e.getType().getName())
                            .collect(Collectors.joining(","))
                    + ")] in the type[" + typeOrTargetRefType.getName() + "]", methodReferenceExpression);
        } else {
            if (parametersWithExactType.length > 0 && isTypeReferingInstanceMethod(typeOrTargetRef, methodRefMethod)) {
                Parameter firstParameter = parametersWithExactType[0];
                Class<?> typeOrTargetClass = typeOrTargetRef.getType().getTypeClass();
                Class<?> firstParameterClass = firstParameter.getType().getTypeClass();
                if (!typeOrTargetClass.isAssignableFrom(firstParameterClass)) {
                    throw new RuntimeParserException("Invalid receiver type: " + firstParameterClass + " is not compatible with " + typeOrTargetClass, typeOrTargetRef);
                }
            }
        }

        methodRefMethod.putNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE, parametersWithExactType);
        MethodVisitor mv = controller.getMethodVisitor();

        boolean isClassExpr = isClassExpr(typeOrTargetRef);

        if (!isClassExpr) {
            if (isConstructorReference) {
                // TODO move the checking code to the Parrot parser
                throw new RuntimeParserException("Constructor reference must be className::new", methodReferenceExpression);
            }

            if (methodRefMethod.isStatic()) {
                ClassExpression classExpression = new ClassExpression(typeOrTargetRefType);
                classExpression.setSourcePosition(typeOrTargetRef);
                typeOrTargetRef = classExpression;
                isClassExpr = true;
            }

            if (!isClassExpr) {
                typeOrTargetRef.visit(controller.getAcg());
            }
        }

        mv.visitInvokeDynamicInsn(
                abstractMethodNode.getName(),
                createAbstractMethodDesc(functionalInterfaceType, typeOrTargetRef),
                createBootstrapMethod(isInterface),
                createBootstrapMethodArguments(
                        abstractMethodDesc,
                        methodRefMethod.isStatic() || isConstructorReference ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL,
                        isConstructorReference ? controller.getClassNode() : typeOrTargetRefType,
                        methodRefMethod)
        );

        if (isClassExpr) {
            controller.getOperandStack().push(redirect);
        } else {
            controller.getOperandStack().replace(redirect, 1);
        }
    }

    private void addSyntheticMethodForConstructorReference(String syntheticMethodName, ClassNode returnType, Parameter[] parametersWithExactType) {
        ArgumentListExpression ctorArgs = args(parametersWithExactType);

        controller.getClassNode().addSyntheticMethod(
                syntheticMethodName,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                returnType,
                parametersWithExactType,
                ClassNode.EMPTY_ARRAY,
                block(
                        returnS(
                                returnType.isArray()
                                        ?
                                        new ArrayExpression(
                                                ClassHelper.make(ArrayTypeUtils.elementType(returnType.getTypeClass())),
                                                null,
                                                ctorArgs.getExpressions()
                                        )
                                        :
                                        ctorX(returnType, ctorArgs)
                        )
                )
        );

    }

    private String createSyntheticMethodForConstructorReference() {
        return controller.getContext().getNextConstructorReferenceSyntheticMethodName(controller.getMethodNode());
    }

    private boolean isConstructorReference(String methodRefName) {
        return "new".equals(methodRefName);
    }

    private static boolean isClassExpr(Expression methodRef) {
        return methodRef instanceof ClassExpression;
    }

    private String createAbstractMethodDesc(ClassNode functionalInterfaceType, Expression methodRef) {
        List<Parameter> methodReferenceSharedVariableList = new LinkedList<>();

        if (!(isClassExpr(methodRef))) {
            ClassNode methodRefTargetType = methodRef.getType();
            prependParameter(methodReferenceSharedVariableList, METHODREF_EXPR_INSTANCE, methodRefTargetType);
        }

        return BytecodeHelper.getMethodDescriptor(functionalInterfaceType.redirect(), methodReferenceSharedVariableList.toArray(Parameter.EMPTY_ARRAY));
    }

    private Parameter[] createParametersWithExactType(MethodNode abstractMethodNode, ClassNode[] inferredParameterTypes) {
        Parameter[] originalParameters = abstractMethodNode.getParameters();

        // We MUST clone the parameters to avoid impacting the original parameter type of SAM
        Parameter[] parameters = GeneralUtils.cloneParams(originalParameters);
        if (parameters == null) {
            parameters = Parameter.EMPTY_ARRAY;
        }

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ClassNode parameterType = parameter.getType();
            ClassNode inferredType = inferredParameterTypes[i];

            if (null == inferredType) {
                continue;
            }

            ClassNode type = convertParameterType(parameterType, inferredType);

            parameter.setType(type);
            parameter.setOriginType(type);
        }

        return parameters;
    }

    private MethodNode findMethodRefMethod(String methodRefName, Parameter[] abstractMethodParameters, Expression typeOrTargetRef, boolean isConstructorReference) {
        if (isConstructorReference) {
            return controller.getClassNode().getMethod(methodRefName, abstractMethodParameters);
        }

        ClassNode typeOrTargetRefType = typeOrTargetRef.getType();
        List<MethodNode> methodNodeList = typeOrTargetRefType.getMethods(methodRefName);
        ClassNode classNode = controller.getClassNode();

        List<MethodNode> candidates = new LinkedList<>();
        for (MethodNode mn : filterMethodsByVisibility(methodNodeList, classNode)) {
            Parameter[] parameters = abstractMethodParameters;
            if (isTypeReferingInstanceMethod(typeOrTargetRef, mn)) {
                if (0 == abstractMethodParameters.length) {
                    continue;
                }

                parameters =
                        new ArrayList<>(Arrays.asList(abstractMethodParameters))
                                .subList(1, abstractMethodParameters.length)
                                .toArray(Parameter.EMPTY_ARRAY);

            }

            if (ParameterUtils.parametersCompatible(parameters, mn.getParameters())) {
                candidates.add(mn);
            }
        }

        return chooseMethodRefMethodCandidate(typeOrTargetRef, candidates);
    }

    private static boolean isTypeReferingInstanceMethod(Expression typeOrTargetRef, MethodNode mn) {  // class::instanceMethod
        return !mn.isStatic() && isClassExpr(typeOrTargetRef);
    }

    /**
     * Choose the best method node for method reference.
     */
    private MethodNode chooseMethodRefMethodCandidate(Expression methodRef, List<MethodNode> candidates) {
        if (1 == candidates.size()) return candidates.get(0);

        return candidates.stream()
                .map(e -> Tuple.tuple(e, matchingScore(e, methodRef)))
                .min((t1, t2) -> Integer.compare(t2.getV2(), t1.getV2()))
                .map(Tuple2::getV1)
                .orElse(null);
    }

    private static Integer matchingScore(MethodNode mn, Expression typeOrTargetRef) {
        ClassNode typeOrTargetRefType = typeOrTargetRef.getType();

        int score = 9;
        for (ClassNode cn = mn.getDeclaringClass(); null != cn && !cn.equals(typeOrTargetRefType); cn = cn.getSuperClass()) {
            score--;
        }
        if (score < 0) {
            score = 0;
        }
        score *= 10;

        boolean isClassExpr = isClassExpr(typeOrTargetRef);
        boolean isStaticMethod = mn.isStatic();

        if (isClassExpr && isStaticMethod || !isClassExpr && !isStaticMethod) {
            score += 9;
        }

        return score;
    }
}
