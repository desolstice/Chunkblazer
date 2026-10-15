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
import com.chunkblazer.gps.plan.GoalMode;
import com.chunkblazer.gps.plan.Plan;
import com.chunkblazer.gps.plan.PlanStep;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.ui.ColorScheme;

/**
 * The Plan tab: goal selector, "Plan now", auto re-plan and the ordered step list. Not unit tested (Swing); rows come
 * from {@link PlanViewModel} and the planning glue lives in {@link PlanRunner}. All methods run on the EDT except the
 * volatile getters {@link #goal()} and {@link #autoReplan()}.
 */
public class GpsPlanPanel extends JPanel
{
	private static final Color SELECTED_BG = new Color(0x3a3a3a);
	private static final Color APPROX = new Color(0xffb74d);
	private static final Color BADGE = new Color(0x64b5f6);

	private final PlanHighlight highlight;
	private final GpsData data;
	private final Function<String, String> questName;

	private final JComboBox<PlanRunner.GoalChoice> goalCombo = new JComboBox<>(PlanRunner.GoalChoice.values());
	private final JComboBox<String> skillCombo = new JComboBox<>(GpsPanel.SKILLS);
	private final JSpinner levelSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 99, 1));
	private final JPanel skillRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
	private final JCheckBox autoBox = new JCheckBox("Auto re-plan", false);
	private final JLabel headerLabel = new JLabel(" ");
	private final JLabel footerLabel = new JLabel(" ");
	private final JPanel list = new JPanel();

	private volatile GoalMode goal = GoalMode.maxPoints();
	private volatile boolean auto;

	private Plan plan;
	private int selected = -1;
	private boolean active;

	public GpsPlanPanel(Runnable planNow, PlanHighlight highlight, GpsData data, Function<String, String> questName)
	{
		super(new BorderLayout());
		this.highlight = highlight;
		this.data = data;
		this.questName = questName;

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		JPanel goalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		goalRow.add(new JLabel("Goal"));
		goalRow.add(goalCombo);
		top.add(goalRow);

		skillRow.add(skillCombo);
		skillRow.add(new JLabel("to"));
		skillRow.add(levelSpinner);
		skillRow.setVisible(false);
		top.add(skillRow);

		goalCombo.addActionListener(e -> goalChanged());
		skillCombo.addActionListener(e -> goalChanged());
		levelSpinner.addChangeListener(e -> goalChanged());

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		JButton planBtn = new JButton("Plan now");
		planBtn.addActionListener(e -> planNow.run());
		autoBox.addActionListener(e -> auto = autoBox.isSelected());
		actions.add(planBtn);
		actions.add(autoBox);
		top.add(actions);
		top.add(headerLabel);
		add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		add(footerLabel, BorderLayout.SOUTH);
		footerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** The goal currently chosen; safe from any thread. */
	public GoalMode goal()
	{
		return goal;
	}

	/** Whether "Auto re-plan" is ticked; safe from any thread. */
	public boolean autoReplan()
	{
		return auto;
	}

	private void goalChanged()
	{
		PlanRunner.GoalChoice choice = (PlanRunner.GoalChoice) goalCombo.getSelectedItem();
		skillRow.setVisible(choice == PlanRunner.GoalChoice.TRAIN_SKILL);
		goal = PlanRunner.goalFor(choice, (String) skillCombo.getSelectedItem(), (Integer) levelSpinner.getValue());
	}

	/** Shows a planner result (or the log-in message). EDT only. */
	public void show(PlanRunner.Result result)
	{
		list.removeAll();
		selected = -1;
		if (result.getMessage() != null)
		{
			plan = null;
			headerLabel.setText(html(result.getMessage()));
			headerLabel.setForeground(APPROX);
			footerLabel.setText(" ");
			highlight.clear();
			refreshList();
			return;
		}
		plan = result.getPlan();
		String version = plan == null ? null : plan.getDataVersion();
		PlanViewModel.View view = PlanViewModel.build(plan, data == null ? null : data.getStatus(), version, questName);
		headerLabel.setText(html(view.getHeader()));
		headerLabel.setForeground(Color.WHITE);
		footerLabel.setText(html(view.getFooter()));
		List<PlanViewModel.Row> rows = view.getRows();
		for (int i = 0; i < rows.size(); i++)
		{
			list.add(rowPanel(i, rows.get(i)));
		}
		select(firstStepIndex());
		refreshList();
	}

	/** Called when the Plan tab becomes the visible tab. EDT only. */
	public void tabShown()
	{
		active = true;
		applyHighlight();
	}

	/** Called when the Plan tab is hidden or the panel closes. EDT only. */
	public void tabHidden()
	{
		active = false;
		highlight.clear();
	}

	private int firstStepIndex()
	{
		return plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty() ? 0 : -1;
	}

	private void select(int index)
	{
		selected = index;
		for (int i = 0; i < list.getComponentCount(); i++)
		{
			list.getComponent(i).setBackground(i == selected ? SELECTED_BG : ColorScheme.DARK_GRAY_COLOR);
		}
		applyHighlight();
	}

	private void applyHighlight()
	{
		if (!active || plan == null || selected < 0 || selected >= plan.getSteps().size())
		{
			highlight.clear();
			return;
		}
		PlanStep step = plan.getSteps().get(selected);
		OptionalInt region = PlanHighlight.regionOf(step);
		if (region.isPresent())
		{
			highlight.set(region.getAsInt(), step.getLabel());
		}
		else
		{
			highlight.clear();
		}
	}

	private void refreshList()
	{
		list.revalidate();
		list.repaint();
	}

	private JPanel rowPanel(int index, PlanViewModel.Row r)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		Color text = r.isUnplannable() ? ColorScheme.MEDIUM_GRAY_COLOR : Color.WHITE;
		Color dim = r.isUnplannable() ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR;

		String order = r.getKind() == PlanViewModel.Kind.INFO || r.isUnplannable() ? "" : r.getOrder() + 1 + ". ";
		String title = order + (r.isApproximate() ? "~ " : "") + r.getTitle();
		p.add(label(title, r.isApproximate() ? APPROX : text));
		if (r.getBadge() != null)
		{
			p.add(label(r.getBadge(), r.isUnplannable() ? dim : BADGE));
		}
		if (r.getTravelText() != null && !r.getTravelText().isEmpty())
		{
			p.add(label("Travel " + r.getTravelText(), dim));
		}
		if (r.getSubtitle() != null && !r.getSubtitle().isEmpty())
		{
			p.add(label(r.getSubtitle(), dim));
		}
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				select(index);
			}
		});
		return p;
	}

	private static JLabel label(String text, Color color)
	{
		JLabel l = new JLabel(html(text));
		l.setForeground(color);
		return l;
	}

	private static String html(String text)
	{
		String esc = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:190px'>" + esc + "</div></html>";
	}
}
