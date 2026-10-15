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

import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataStatus;
import com.chunkblazer.gps.eligibility.Eligibility;
import com.chunkblazer.gps.model.Missing;
import com.chunkblazer.gps.model.PlayerState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
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
	static final String[] SKILLS = {
		"ATTACK", "DEFENCE", "STRENGTH", "HITPOINTS", "RANGED", "PRAYER", "MAGIC", "COOKING", "WOODCUTTING",
		"FLETCHING", "FISHING", "FIREMAKING", "CRAFTING", "SMITHING", "MINING", "HERBLORE", "AGILITY", "THIEVING",
		"SLAYER", "FARMING", "RUNECRAFT", "HUNTER", "CONSTRUCTION"
	};

	private GpsPanelModel model;
	/** Asks the plugin for a real snapshot plus a model over the current task list; delivers from any thread. */
	private final Consumer<BiConsumer<PlayerState, GpsPanelModel>> snapshotRequester;

	private final JLabel modeLabel = new JLabel("Real account");
	private final SimulationSession session;
	private final JCheckBox doableBox = new JCheckBox("Doable", true);
	private final JCheckBox blockedBox = new JCheckBox("Blocked", true);
	private final JCheckBox unknownBox = new JCheckBox("Unknown", true);
	private final JCheckBox questsBox = new JCheckBox("Quests", false);
	private final JTextField search = new JTextField();
	private final JLabel summaryLabel = new JLabel(" ");
	private final JLabel loginHint = new JLabel("Log in to read your real skills");
	private final JPanel list = new JPanel();
	private final Timer coalesce;

	private final JPanel tasksTab = new JPanel(new BorderLayout());
	private final JTabbedPane tabs = new JTabbedPane();
	private final GpsPlanPanel planPanel;

	private final SimulatePanel simulatePanel;
	private PlayerState realState = PlayerState.freshAccount();
	private boolean loggedIn = true;

	public GpsPanel(GpsPanelModel model, GpsData data, Consumer<BiConsumer<PlayerState, GpsPanelModel>> snapshotRequester,
		GpsPlanPanel planPanel, SimulatePanel simulatePanel, SimulationSession session)
	{
		super(false);
		this.model = model;
		this.snapshotRequester = snapshotRequester;
		this.planPanel = planPanel;
		this.session = session;
		this.simulatePanel = simulatePanel;
		simulatePanel.setRealSource(() -> realState);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		tasksTab.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		tabs.addTab("Tasks", tasksTab);
		tabs.addTab("Plan", planPanel);
		tabs.addTab("Simulate", simulatePanel);
		tabs.addChangeListener(e -> syncPlanTab());
		add(tabs, BorderLayout.CENTER);
		GpsDataStatus dataStatus = data == null ? null : data.getStatus();
		String help = GpsHelpText.forStatus(dataStatus, dataStatus == null ? null : dataStatus.getDir());
		if (!help.isEmpty())
		{
			JLabel banner = wrapped(help, UNKNOWN);
			banner.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			add(banner, BorderLayout.NORTH);
		}

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton refresh = new JButton("Refresh");
		refresh.addActionListener(e -> refresh());
		modeRow.add(modeLabel);
		modeRow.add(refresh);
		top.add(modeRow);

		String status = data == null || data.getStatus() == null ? "GPS data status unavailable." : data.getStatus().describe();
		if (help.isEmpty())
		{
			// The banner already carries the status text when the data is incomplete.
			top.add(wrapped(status, ColorScheme.LIGHT_GRAY_COLOR));
		}

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

		tasksTab.add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		tasksTab.add(scroll, BorderLayout.CENTER);
	}

	/** The simulated state while Simulate is on, else null (real mode). Safe from any thread. */
	public PlayerState simulatedState()
	{
		return session.view();
	}

	private void syncPlanTab()
	{
		if (tabs.getSelectedComponent() == planPanel)
		{
			planPanel.tabShown();
		}
		else
		{
			planPanel.tabHidden();
		}
	}

	@Override
	public void onDeactivate()
	{
		planPanel.tabHidden();
	}

	@Override
	public void onActivate()
	{
		syncPlanTab();
		if (!session.isActive())
		{
			refresh();
		}
	}

	/** Re-reads the real state (real mode) and redraws. Safe to call from any thread. */
	public void refresh()
	{
		if (session.isActive())
		{
			SwingUtilities.invokeLater(coalesce::restart);
			return;
		}
		snapshotRequester.accept((state, freshModel) -> SwingUtilities.invokeLater(() ->
		{
			realState = state;
			model = freshModel;
			render();
		}));
	}

	private void render()
	{
		PlayerState sim = session.view();
		modeLabel.setText(sim == null ? "Real account" : "Simulated (edit in Simulate tab)");
		PlayerState state = sim != null ? sim : realState;
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
		this.loggedIn = loggedIn;
		loginHint.setVisible(!loggedIn);
		simulatePanel.setRealAvailable(loggedIn);
	}
}
