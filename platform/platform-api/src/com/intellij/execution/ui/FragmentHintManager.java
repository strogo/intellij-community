// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.ui;

import com.intellij.openapi.actionSystem.EmptyAction;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FragmentHintManager {
  private final List<SettingsEditorFragment<?, ?>> myFragments = new ArrayList<>();
  private final @NotNull Consumer<? super String> myHintConsumer;
  private final String myDefaultHint;

  public FragmentHintManager(@NotNull Consumer<? super String> hintConsumer, @Nullable String defaultHint) {
    myHintConsumer = hintConsumer;
    myDefaultHint = defaultHint;
    hintConsumer.consume(defaultHint);
  }

  public void registerFragments(Collection<? extends SettingsEditorFragment<?, ?>> fragments) {
    fragments.forEach(fragment -> registerFragment(fragment));
  }

  public void registerFragment(SettingsEditorFragment<?, ?> fragment) {
    myFragments.add(fragment);
    JComponent component = fragment.component();
    if (component instanceof RawCommandLineEditor) {
      component = ((RawCommandLineEditor)component).getEditorField();
    }
    component.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseEntered(MouseEvent e) {
        showHint(fragment);
      }

      @Override
      public void mouseExited(MouseEvent e) {
        showHint(null);
      }
    });

    component.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        showHint(fragment);
      }

      @Override
      public void focusLost(FocusEvent e) {
        showHint(null);
      }
    });
  }

  private void showHint(@Nullable SettingsEditorFragment<?, ?> fragment) {
    String hint = myDefaultHint;
    if (fragment != null) {
      hint = fragment.getHint();
    }
    else {
      fragment = ContainerUtil.find(myFragments, f -> f.getEditorComponent().hasFocus());
      if (fragment != null) {
        hint = fragment.getHint();
      }
    }
    if (fragment != null) {
      ShortcutSet shortcut = ActionUtil.getMnemonicAsShortcut(new EmptyAction(fragment.getName(), null, null));
      if (shortcut != null && shortcut.getShortcuts().length > 0) {
        String text = KeymapUtil.getShortcutsText(new Shortcut[]{ArrayUtil.getLastElement(shortcut.getShortcuts())});
        hint = hint == null ? text : hint + ". " + text;
      }
    }
    myHintConsumer.consume(hint == null ? myDefaultHint : hint);
  }
}
