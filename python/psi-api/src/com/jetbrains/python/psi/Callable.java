/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.psi;

import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Something that can be called, passed parameters to, and return something back.

 * @author dcheryasov
 */
public interface Callable extends PyTypedElement, PyQualifiedNameOwner {

  /**
   * @return a list of parameters passed to this callable, possibly empty.
   */
  @NotNull
  PyParameterList getParameterList();

  /**
   * @return the type of returned value.
   */
  @Nullable
  PyType getReturnType(@NotNull TypeEvalContext context, @Nullable PyQualifiedExpression callSite);

  /**
   * @return a methods returns itself, non-method callables return null.
   */
  @Nullable
  PyFunction asMethod();
}
