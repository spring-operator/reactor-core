/*
 * Copyright (c) 2019-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.tools.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import reactor.core.publisher.Hooks;

public class ReactorDebugAgent {

	private static Instrumentation instrumentation;

	public static synchronized void init() {
		if (instrumentation != null) {
			return;
		}
		instrumentation = ByteBuddyAgent.install();

		ClassFileTransformer transformer = new PublicMethodsClassFileTransformer();
		instrumentation.addTransformer(transformer, true);
		try {
			instrumentation.retransformClasses(Hooks.class);
		}
		catch (UnmodifiableClassException e) {
			throw new RuntimeException(e);
		}
		instrumentation.removeTransformer(transformer);

		transformer = new ClassFileTransformer() {
			@Override
			public byte[] transform(
					ClassLoader loader,
					String className,
					Class<?> clazz,
					ProtectionDomain protectionDomain,
					byte[] bytes
			) {
				if (loader == null) {
					return null;
				}

				if (
						className == null ||
								className.startsWith("java/") ||
								className.startsWith("jdk/") ||
								className.startsWith("sun/") ||
								className.startsWith("com/sun/") ||
								className.startsWith("reactor/core/")
				) {
					return null;
				}

				if (
						clazz != null && (
								clazz.isPrimitive() ||
										clazz.isArray() ||
										clazz.isAnnotation() ||
										clazz.isSynthetic()
						)
				) {
					return null;
				}

				ClassReader cr = new ClassReader(bytes);
				ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

				AtomicBoolean changed = new AtomicBoolean();
				ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM7, cw) {

					private String currentClassName = "";

					private String currentSource = "";

					@Override
					public void visitSource(String source, String debug) {
						super.visitSource(source, debug);
						currentSource = source;
					}

					@Override
					public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
						super.visit(version, access, name, signature, superName, interfaces);
						currentClassName = name;
					}

					@Override
					public MethodVisitor visitMethod(int access, String currentMethod, String descriptor, String signature, String[] exceptions) {
						MethodVisitor visitor = super.visitMethod(access, currentMethod, descriptor, signature, exceptions);

						return new AssemblyInfoAddingMethodVisitor(visitor, currentMethod, currentClassName, currentSource, changed);
					}
				};

				cr.accept(classVisitor, 0);

				if (!changed.get()) {
					return null;
				}

				return cw.toByteArray();
			}
		};

		instrumentation.addTransformer(transformer, true);
	}

	public static synchronized void processExistingClasses() {
		if (instrumentation == null) {
			throw new IllegalStateException("Must be initialized first!");
		}

		try {
			Class[] classes = Stream
					.of(instrumentation.getInitiatedClasses(ClassLoader.getSystemClassLoader()))
					.filter(aClass -> {
						try {
							if (aClass.getClassLoader() == null) return false;
							if (aClass.isPrimitive() || aClass.isArray() || aClass.isInterface()) return false;
							if (aClass.isAnnotation() || aClass.isSynthetic()) return false;
							String name = aClass.getName();
							if (name == null) return false;
							if (name.startsWith("[")) return false;
							if (name.startsWith("java.")) return false;
							if (name.startsWith("sun.")) return false;
							if (name.startsWith("com.sun.")) return false;
							if (name.startsWith("jdk.")) return false;
							if (name.startsWith("reactor.core.")) return false;

							// May trigger NoClassDefFoundError, fail fast
							aClass.getConstructors();
						}
						catch (NoClassDefFoundError e) {
							return false;
						}

						return true;
					})
					.toArray(Class[]::new);

			instrumentation.retransformClasses(classes);
		}
		catch (Throwable e) {
			e.printStackTrace();
			// Some classes fail to re-transform (e.g. worker.org.gradle.internal.UncheckedException)
			// See https://bugs.openjdk.java.net/browse/JDK-8014229
		}
	}


	static class AssemblyInfoAddingMethodVisitor extends MethodVisitor {

		private final String currentMethod;

		private final String currentClassName;

		private final String currentSource;

		private final AtomicBoolean changed;

		private int currentLine = -1;

		AssemblyInfoAddingMethodVisitor(
				MethodVisitor visitor,
				String currentMethod,
				String currentClassName,
				String currentSource,
				AtomicBoolean changed
		) {
			super(Opcodes.ASM7, visitor);
			this.currentMethod = currentMethod;
			this.currentClassName = currentClassName;
			this.currentSource = currentSource;
			this.changed = changed;
		}

		@Override
		public void visitLineNumber(int line, Label start) {
			super.visitLineNumber(line, start);
			currentLine = line;
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
			super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
			switch (owner) {
				case "reactor/core/publisher/Flux":
				case "reactor/core/publisher/Mono":
				case "reactor/core/publisher/ParallelFlux":
					if ("checkpoint".equals(name)) {
						return;
					}
					String returnType = Type.getReturnType(descriptor).getInternalName();
					if (!returnType.startsWith("reactor/core/publisher/")) {
						return;
					}

					changed.set(true);
					String callSite = String.format(
							"\t%s.%s\n\t%s.%s(%s:%d)\n",
							owner.replace("/", "."), name,
							currentClassName.replace("/", "."), currentMethod, currentSource, currentLine
					);
					super.visitLdcInsn(callSite);
					super.visitMethodInsn(
							Opcodes.INVOKESTATIC,
							"reactor/core/publisher/Hooks",
							"addAssemblyInfo",
							"(Lreactor/core/CorePublisher;Ljava/lang/String;)Lreactor/core/CorePublisher;",
							false
					);
					super.visitTypeInsn(Opcodes.CHECKCAST, returnType);
					break;
			}
		}
	}

	static class PublicMethodsClassFileTransformer implements ClassFileTransformer {

		@Override
		public byte[] transform(
				ClassLoader loader,
				String className,
				Class<?> clazz,
				ProtectionDomain protectionDomain,
				byte[] bytes
		) {
			if ("reactor/core/publisher/Hooks".equals(className)) {
				ClassReader cr = new ClassReader(bytes);
				ClassWriter cw = new ClassWriter(cr, 0);

				ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM7, cw) {
					@Override
					public MethodVisitor visitMethod(int access,
							String name,
							String descriptor,
							String signature,
							String[] exceptions) {
						if ("addAssemblyInfo".equals(name)) {
							access |= Opcodes.ACC_PUBLIC;
						}
						return super.visitMethod(access, name, descriptor, signature, exceptions);
					}
				};

				cr.accept(classVisitor, 0);
				return cw.toByteArray();
			}
			return null;
		}
	}
}