/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Jun 18, 2002
 * Time: 9:12:05 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.openapi.editor.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.MultipleCaretListener;
import com.intellij.openapi.editor.ex.DocumentBulkUpdateListener;
import com.intellij.openapi.editor.ex.PrioritizedDocumentListener;
import com.intellij.openapi.editor.impl.event.DocumentEventImpl;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class CaretModelImpl implements CaretModel, PrioritizedDocumentListener, Disposable {
  private final EditorImpl myEditor;
  
  private final EventDispatcher<MultipleCaretListener> myCaretListeners = EventDispatcher.create(MultipleCaretListener.class);
  private final Map<CaretListener, MultipleCaretListener> myListenerMap = new HashMap<CaretListener, MultipleCaretListener>();
  private final boolean mySupportsMultipleCarets = Registry.is("editor.allow.multiple.carets");

  private TextAttributes myTextAttributes;

  boolean myIgnoreWrongMoves = false;
  boolean myIsInUpdate;
  boolean isDocumentChanged;

  private final LinkedList<CaretImpl> myCarets = new LinkedList<CaretImpl>();
  private CaretImpl myCurrentCaret; // active caret in the context of 'runForEachCaret' call
  private boolean myPerformCaretMergingAfterCurrentOperation;

  public CaretModelImpl(EditorImpl editor) {
    myEditor = editor;
    myCarets.add(new CaretImpl(myEditor));

    DocumentBulkUpdateListener bulkUpdateListener = new DocumentBulkUpdateListener() {
      @Override
      public void updateStarted(@NotNull Document doc) {
        for (CaretImpl caret : myCarets) {
          caret.onBulkDocumentUpdateStarted(doc);
        }
      }

      @Override
      public void updateFinished(@NotNull final Document doc) {
        doWithCaretMerging(new Runnable() {
          @Override
          public void run() {
            for (CaretImpl caret : myCarets) {
              caret.onBulkDocumentUpdateFinished(doc);
            }
          }
        });
      }
    };
    ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(DocumentBulkUpdateListener.TOPIC, bulkUpdateListener);
  }

  @Override
  public void documentChanged(final DocumentEvent e) {
    isDocumentChanged = true;
    try {
      myIsInUpdate = false;
      doWithCaretMerging(new Runnable() {
        @Override
        public void run() {
          for (CaretImpl caret : myCarets) {
            caret.updateCaretPosition((DocumentEventImpl)e);
          }
        }
      });
    }
    finally {
      isDocumentChanged = false;
    }
  }

  @Override
  public void beforeDocumentChange(DocumentEvent e) {
    myIsInUpdate = true;
  }

  @Override
  public int getPriority() {
    return EditorDocumentPriorities.CARET_MODEL;
  }

  @Override
  public void dispose() {
    for (CaretImpl caret : myCarets) {
      Disposer.dispose(caret);
    }
  }

  public void setIgnoreWrongMoves(boolean ignoreWrongMoves) {
    myIgnoreWrongMoves = ignoreWrongMoves;
  }

  public void updateVisualPosition() {
    for (CaretImpl caret : myCarets) {
      caret.updateVisualPosition();
    }
  }

  @Override
  public void moveCaretRelatively(final int columnShift, final int lineShift, final boolean withSelection, final boolean blockSelection, final boolean scrollToCaret) {
    getCurrentCaret().moveCaretRelatively(columnShift, lineShift, withSelection, blockSelection, scrollToCaret);
  }

  @Override
  public void moveToLogicalPosition(@NotNull LogicalPosition pos) {
    getCurrentCaret().moveToLogicalPosition(pos);
  }

  @Override
  public void moveToVisualPosition(@NotNull VisualPosition pos) {
    getCurrentCaret().moveToVisualPosition(pos);
  }

  @Override
  public void moveToOffset(int offset) {
    getCurrentCaret().moveToOffset(offset);
  }

  @Override
  public void moveToOffset(int offset, boolean locateBeforeSoftWrap) {
    getCurrentCaret().moveToOffset(offset, locateBeforeSoftWrap);
  }

  @Override
  public boolean isUpToDate() {
    return getCurrentCaret().isUpToDate();
  }

  @NotNull
  @Override
  public LogicalPosition getLogicalPosition() {
    return getCurrentCaret().getLogicalPosition();
  }

  @NotNull
  @Override
  public VisualPosition getVisualPosition() {
    return getCurrentCaret().getVisualPosition();
  }

  @Override
  public int getOffset() {
    return getCurrentCaret().getOffset();
  }

  @Override
  public int getVisualLineStart() {
    return getCurrentCaret().getVisualLineStart();
  }

  @Override
  public int getVisualLineEnd() {
    return getCurrentCaret().getVisualLineEnd();
  }

  int getWordAtCaretStart() {
    return getCurrentCaret().getWordAtCaretStart();
  }

  int getWordAtCaretEnd() {
    return getCurrentCaret().getWordAtCaretEnd();
  }

  @Override
  public void addCaretListener(@NotNull final CaretListener listener) {
    MultipleCaretListener newListener;
    if (listener instanceof MultipleCaretListener) {
      newListener = (MultipleCaretListener)listener;
    }
    else {
      newListener = new MultipleCaretListener() {
        @Override
        public void caretAdded(CaretEvent e) {

        }

        @Override
        public void caretRemoved(CaretEvent e) {

        }

        @Override
        public void caretPositionChanged(CaretEvent e) {
          listener.caretPositionChanged(e);
        }
      };
    }
    myListenerMap.put(listener, newListener);
    myCaretListeners.addListener(newListener);
  }

  @Override
  public void removeCaretListener(@NotNull CaretListener listener) {
    MultipleCaretListener newListener = myListenerMap.remove(listener);
    if (newListener != null) {
      myCaretListeners.removeListener(newListener);
    }
  }

  @Override
  public TextAttributes getTextAttributes() {
    if (myTextAttributes == null) {
      myTextAttributes = new TextAttributes();
      myTextAttributes.setBackgroundColor(myEditor.getColorsScheme().getColor(EditorColors.CARET_ROW_COLOR));
    }

    return myTextAttributes;
  }

  public void reinitSettings() {
    myTextAttributes = null;
  }

  @Override
  public boolean supportsMultipleCarets() {
    return mySupportsMultipleCarets;
  }

  @Override
  @NotNull
  public CaretImpl getCurrentCaret() {
    return ApplicationManager.getApplication().isDispatchThread() && myCurrentCaret != null ? myCurrentCaret : getPrimaryCaret();
  }

  @Override
  @NotNull
  public CaretImpl getPrimaryCaret() {
    synchronized (myCarets) {
      return myCarets.get(myCarets.size() - 1);
    }
  }

  @Override
  @NotNull
  public Collection<Caret> getAllCarets() {
    List<Caret> carets;
    synchronized (myCarets) {
      carets = new ArrayList<Caret>(myCarets);
    }
    Collections.sort(carets, CaretPositionComparator.INSTANCE);
    return carets;
  }

  @Nullable
  @Override
  public Caret getCaretAt(@NotNull VisualPosition pos) {
    synchronized (myCarets) {
      for (CaretImpl caret : myCarets) {
        if (caret.getVisualPosition().equals(pos)) {
          return caret;
        }
      }
      return null;
    }
  }

  @Nullable
  @Override
  public Caret addCaret(@NotNull VisualPosition pos) {
    myEditor.assertIsDispatchThread();
    CaretImpl caret = new CaretImpl(myEditor);
    caret.moveToVisualPosition(pos, false);
    if (addCaret(caret)) {
      return caret;
    }
    else {
      Disposer.dispose(caret);
      return null;
    }
  }

  boolean addCaret(CaretImpl caretToAdd) {
    for (CaretImpl caret : myCarets) {
      VisualPosition newVisualPosition = caretToAdd.getVisualPosition();
      int newOffset = myEditor.logicalPositionToOffset(myEditor.visualToLogicalPosition(newVisualPosition));
      if (caret.getVisualPosition().equals(newVisualPosition) || newOffset >= caret.getSelectionStart() && newOffset <= caret.getSelectionEnd()) {
        return false;
      }
    }
    synchronized (myCarets) {
      myCarets.add(caretToAdd);
    }
    fireCaretAdded(caretToAdd);
    return true;
  }

  @Override
  public boolean removeCaret(@NotNull Caret caret) {
    myEditor.assertIsDispatchThread();
    if (myCarets.size() <= 1 || !(caret instanceof CaretImpl)) {
      return false;
    }
    synchronized (myCarets) {
      if (!myCarets.remove(caret)) {
        return false;
      }
    }
    fireCaretRemoved(caret);
    Disposer.dispose(caret);
    return true;
  }

  @Override
  public void removeSecondaryCarets() {
    myEditor.assertIsDispatchThread();
    if (!supportsMultipleCarets()) {
      return;
    }
    ListIterator<CaretImpl> caretIterator = myCarets.listIterator(myCarets.size() - 1);
    while (caretIterator.hasPrevious()) {
      CaretImpl caret = caretIterator.previous();
      synchronized (myCarets) {
        caretIterator.remove();
      }
      fireCaretRemoved(caret);
      Disposer.dispose(caret);
    }
  }

  @Override
  public void runForEachCaret(@NotNull final CaretAction action) {
    myEditor.assertIsDispatchThread();
    if (!supportsMultipleCarets()) {
      action.perform(getPrimaryCaret());
      return;
    }
    if (myCurrentCaret != null) {
      throw new IllegalStateException("Current caret is defined, cannot operate on other ones");
    }
    doWithCaretMerging(new Runnable() {
      public void run() {
        try {
          Collection<Caret> sortedCarets = getAllCarets();
          for (Caret caret : sortedCarets) {
            myCurrentCaret = (CaretImpl)caret;
            action.perform(caret);
          }
        }
        finally {
          myCurrentCaret = null;
        }
      }
    });
  }

  @Override
  public void runBatchCaretOperation(@NotNull Runnable runnable) {
    doWithCaretMerging(runnable);
  }

  private void mergeOverlappingCaretsAndSelections() {
    if (!supportsMultipleCarets() || myCarets.size() <= 1) {
      return;
    }
    LinkedList<CaretImpl> carets = new LinkedList<CaretImpl>(myCarets);
    Collections.sort(carets, CaretPositionComparator.INSTANCE);
    ListIterator<CaretImpl> it = carets.listIterator();
    while (it.hasNext()) {
      CaretImpl prevCaret = null;
      if (it.hasPrevious()) {
        prevCaret = it.previous();
        it.next();
      }
      CaretImpl currCaret = it.next();
      if (prevCaret != null && (currCaret.getVisualPosition().equals(prevCaret.getVisualPosition())
                                || Math.max(currCaret.getSelectionStart(), prevCaret.getSelectionStart())
                                   < Math.min(currCaret.getSelectionEnd(), prevCaret.getSelectionEnd()))) {
        int newSelectionStart = Math.min(currCaret.getSelectionStart(), prevCaret.getSelectionStart());
        int newSelectionEnd = Math.max(currCaret.getSelectionEnd(), prevCaret.getSelectionEnd());
        CaretImpl toRetain, toRemove;
        if (currCaret.getOffset() >= prevCaret.getSelectionStart() && currCaret.getOffset() <= prevCaret.getSelectionEnd()) {
          toRetain = prevCaret;
          toRemove = currCaret;
          it.remove();
          it.previous();
        }
        else {
          toRetain = currCaret;
          toRemove = prevCaret;
          it.previous();
          it.previous();
          it.remove();
        }
        removeCaret(toRemove);
        if (newSelectionStart < newSelectionEnd) {
          toRetain.setSelection(newSelectionStart, newSelectionEnd);
        }
      }
    }
  }

  void doWithCaretMerging(Runnable runnable) {
    if (myPerformCaretMergingAfterCurrentOperation) {
      runnable.run();
    }
    else {
      myPerformCaretMergingAfterCurrentOperation = true;
      try {
        runnable.run();
      }
      finally {
        myPerformCaretMergingAfterCurrentOperation = false;
      }
      mergeOverlappingCaretsAndSelections();
    }
  }

  @Override
  public void setCarets(@NotNull final List<LogicalPosition> caretPositions, @NotNull final List<? extends Segment> selections) {
    myEditor.assertIsDispatchThread();
    if (caretPositions.isEmpty()) {
      throw new IllegalArgumentException("At least one caret should exist");
    }
    if (caretPositions.size() != selections.size()) {
      throw new IllegalArgumentException("Position and selection lists are of different size");
    }
    doWithCaretMerging(new Runnable() {
      public void run() {
        int index = 0;
        int oldCaretCount = myCarets.size();
        Iterator<CaretImpl> caretIterator = myCarets.iterator();
        Iterator<? extends Segment> selectionIterator = selections.iterator();
        for (LogicalPosition caretPosition : caretPositions) {
          CaretImpl caret;
          boolean caretAdded;
          if (index++ < oldCaretCount) {
            caret = caretIterator.next();
            caretAdded = false;
          }
          else {
            caret = new CaretImpl(myEditor);
            caret.moveToLogicalPosition(caretPosition, false, null, false);
            synchronized (myCarets) {
              myCarets.add(caret);
            }
            fireCaretAdded(caret);
            caretAdded = true;
          }
          if (caretPosition != null && !caretAdded) {
            caret.moveToLogicalPosition(caretPosition);
          }
          Segment selection = selectionIterator.next();
          if (selection != null) {
            caret.setSelection(selection.getStartOffset(), selection.getEndOffset());
          }
        }
        int caretsToRemove = myCarets.size() - caretPositions.size();
        for (int i = 0; i < caretsToRemove; i++) {
          CaretImpl caret;
          synchronized (myCarets) {
            caret = myCarets.removeLast();
          }
          fireCaretRemoved(caret);
          Disposer.dispose(caret);
        }
      }
    });
  }

  void fireCaretPositionChanged(CaretEvent caretEvent) {
    myCaretListeners.getMulticaster().caretPositionChanged(caretEvent);
  }

  void fireCaretAdded(@NotNull Caret caret) {
    myCaretListeners.getMulticaster().caretAdded(new CaretEvent(myEditor, caret, caret.getLogicalPosition(), caret.getLogicalPosition()));
  }

  void fireCaretRemoved(@NotNull Caret caret) {
    myCaretListeners.getMulticaster().caretRemoved(new CaretEvent(myEditor, caret, caret.getLogicalPosition(), caret.getLogicalPosition()));
  }

  private static class VisualPositionComparator implements Comparator<VisualPosition> {
    private static final VisualPositionComparator INSTANCE = new VisualPositionComparator();

    @Override
    public int compare(VisualPosition o1, VisualPosition o2) {
      if (o1.line != o2.line) {
        return o1.line - o2.line;
      }
      return o1.column - o2.column;
    }
  }

  private static class CaretPositionComparator implements Comparator<Caret> {
    private static final CaretPositionComparator INSTANCE = new CaretPositionComparator();

    @Override
    public int compare(Caret o1, Caret o2) {
      return VisualPositionComparator.INSTANCE.compare(o1.getVisualPosition(), o2.getVisualPosition());
    }
  }
}
