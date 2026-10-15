/*
 * Copyright (c) 2026, Vani-Lab
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazer.gps.ui;

import com.chunkblazer.gps.adapter.SimulatedState;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.eligibility.Eligibility;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Progression GPS sidebar panel: doable/blocked task list and quests, for the real account or a simulated one.
 * Not unit tested (Swing); all model logic lives in {@link GpsPanelModel}.
 */
public class GpsPanel extends PluginPanel
{
	private static final int MAX_ROWS = 300;
	private static final Color DOABLE = new Color(0x4caf50);
	private static final Color BLOCKED = new Color(0xe57373);
	private static final Color UNKNOWN = new Color(0xffb74d);
	private static final String[] SKILLS = {
		"ATTACK", "DEFENCE", "STRENGTH", "HITPOINTS", "RANGED", "PRAYER", "MAGIC", "COOKING", "WOODCUTTING",
		"FLETCHING", "FISHING", "FIREMAKING", "CRAFTING", "SMITHING", "MINING", "HERBLORE", "AGILITY", "THIEVING",
		"SLAYER", "FARMING", "RUNECRAFT", "HUNTER", "CONSTRUCTION"
	};

	private GpsPanelModel model;
	/** Asks the plugin for a real snapshot plus a model over the current task list; delivers from any thread. */
	private final Consumer<BiConsumer<PlayerState, GpsPanelModel>> snapshotRequester;

	private final JToggleButton simulateToggle = new JToggleButton("Simulate");
	private final JCheckBox doableBox = new JCheckBox("Doable", true);
	private final JCheckBox blockedBox = new JCheckBox("Blocked", true);
	private final JCheckBox unknownBox = new JCheckBox("Unknown", true);
	private final JCheckBox questsBox = new JCheckBox("Quests", false);
	private final JTextField search = new JTextField();
	private final JLabel summaryLabel = new JLabel(" ");
	private final JLabel loginHint = new JLabel("Log in to read your real skills");
	private final JPanel levelEditor = new JPanel(new GridLayout(0, 2, 4, 2));
	private final JPanel list = new JPanel();
	private final Timer coalesce;

	private PlayerState realState = PlayerState.freshAccount();
	private SimulatedState simulated;

	public GpsPanel(GpsPanelModel model, GpsData data, Consumer<BiConsumer<PlayerState, GpsPanelModel>> snapshotRequester)
	{
		super(false);
		this.model = model;
		this.snapshotRequester = snapshotRequester;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		simulateToggle.addActionListener(e -> onModeChanged());
		JButton refresh = new JButton("Refresh");
		refresh.addActionListener(e -> refresh());
		modeRow.add(new JLabel("Real /"));
		modeRow.add(simulateToggle);
		modeRow.add(refresh);
		top.add(modeRow);

		String status = data == null || data.getStatus() == null ? "GPS data status unavailable." : data.getStatus().describe();
		top.add(wrapped(status, ColorScheme.LIGHT_GRAY_COLOR));

		JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		for (JCheckBox b : new JCheckBox[]{doableBox, blockedBox, unknownBox, questsBox})
		{
			b.addActionListener(e -> render());
			boxes.add(b);
		}
		top.add(boxes);

		coalesce = new Timer(250, e -> render());
		coalesce.setRepeats(false);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				coalesce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				coalesce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				coalesce.restart();
			}
		});
		top.add(search);
		loginHint.setForeground(UNKNOWN);
		loginHint.setVisible(false);
		top.add(loginHint);
		top.add(summaryLabel);

		levelEditor.setVisible(false);
		for (String skill : SKILLS)
		{
			JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
			spinner.setName(skill);
			spinner.addChangeListener(e ->
			{
				if (simulated != null)
				{
					simulated.setLevel(skill, (Integer) spinner.getValue());
					coalesce.restart();
				}
			});
			levelEditor.add(new JLabel(skill.substring(0, 1) + skill.substring(1).toLowerCase()));
			levelEditor.add(spinner);
		}
		top.add(levelEditor);
		add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
	}

	@Override
	public void onActivate()
	{
		if (!simulateToggle.isSelected())
		{
			refresh();
		}
	}

	/** Re-reads the real state (real mode) and redraws. Safe to call from any thread. */
	public void refresh()
	{
		if (simulateToggle.isSelected())
		{
			SwingUtilities.invokeLater(this::render);
			return;
		}
		snapshotRequester.accept((state, freshModel) -> SwingUtilities.invokeLater(() ->
		{
			realState = state;
			model = freshModel;
			render();
		}));
	}

	private void onModeChanged()
	{
		if (simulateToggle.isSelected())
		{
			simulated = SimulatedState.fromReal(realState);
			PlayerState s = simulated.build();
			for (Component c : levelEditor.getComponents())
			{
				if (c instanceof JSpinner)
				{
					JSpinner sp = (JSpinner) c;
					sp.setValue(s.getLevel(sp.getName()));
				}
			}
			levelEditor.setVisible(true);
			render();
		}
		else
		{
			levelEditor.setVisible(false);
			simulated = null;
			refresh();
		}
	}

	private void render()
	{
		PlayerState state = simulateToggle.isSelected() && simulated != null ? simulated.build() : realState;
		GpsPanelModel.Filter f = GpsPanelModel.Filter.all();
		f.setShowDoable(doableBox.isSelected());
		f.setShowBlocked(blockedBox.isSelected());
		f.setShowUnknown(unknownBox.isSelected());
		f.setText(search.getText());

		List<GpsPanelModel.Row> rows = model.rows(state, f);
		GpsPanelModel.Summary sum = model.summary(rows);
		summaryLabel.setText("<html>" + sum.getDoable() + " doable, " + sum.getBlocked() + " blocked, "
			+ sum.getUnknown() + " unknown</html>");

		list.removeAll();
		int shown = 0;
		for (GpsPanelModel.Row r : rows)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			list.add(entry(r.getTask().getName() == null ? r.getTask().getTaskId() : r.getTask().getName(), r.getEligibility()));
		}
		if (rows.size() > MAX_ROWS)
		{
			list.add(wrapped("... " + (rows.size() - MAX_ROWS) + " more (narrow the filter)", ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (questsBox.isSelected())
		{
			list.add(wrapped("Quests", Color.WHITE));
			String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase();
			int qShown = 0;
			int qTotal = 0;
			for (GpsPanelModel.QuestRow q : model.questRows(state))
			{
				Eligibility.Status st = q.getEligibility().getStatus();
				boolean ok = st == Eligibility.Status.DOABLE ? f.isShowDoable()
					: st == Eligibility.Status.BLOCKED ? f.isShowBlocked() : f.isShowUnknown();
				String name = q.getName() == null ? q.getId() : q.getName();
				if (!ok || !(needle.isEmpty() || name.toLowerCase().contains(needle) || q.getId().toLowerCase().contains(needle)))
				{
					continue;
				}
				qTotal++;
				if (qShown < MAX_ROWS)
				{
					qShown++;
					list.add(entry(name, q.getEligibility()));
				}
			}
			if (qTotal > qShown)
			{
				list.add(wrapped("... " + (qTotal - qShown) + " more quests", ColorScheme.LIGHT_GRAY_COLOR));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private static JPanel entry(String name, Eligibility e)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
		Color c = e.getStatus() == Eligibility.Status.DOABLE ? DOABLE
			: e.getStatus() == Eligibility.Status.BLOCKED ? BLOCKED : UNKNOWN;
		p.add(wrapped(name, c));
		if (e.getStatus() != Eligibility.Status.DOABLE)
		{
			for (Missing m : e.getMissing())
			{
				p.add(wrapped("  - " + m.getText(), ColorScheme.LIGHT_GRAY_COLOR));
			}
		}
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	private static JLabel wrapped(String text, Color color)
	{
		String esc = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		JLabel l = new JLabel("<html><div style='width:190px'>" + esc + "</div></html>");
		l.setForeground(color);
		return l;
	}

	/** Shows a hint while real mode has no logged-in character to read. EDT only. */
	public void setLoggedIn(boolean loggedIn)
	{
		loginHint.setVisible(!loggedIn);
	}
}
