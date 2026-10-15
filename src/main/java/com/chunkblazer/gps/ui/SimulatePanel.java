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

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.ItemDef;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/**
 * Simulate tab: pick a starting point, then edit chunks, levels, quests, items, max hit and task points of the
 * shared {@link SimulationSession}. Not unit tested (Swing); the logic lives in the session and
 * {@link ChunkPickerModel}. EDT only.
 */
public class SimulatePanel extends JPanel
{
	private static final int MAX_ROWS = 150;
	private static final int MAX_ITEM_RESULTS = 60;

	private final SimulationSession session;
	private final GpsData data;
	private final QuestCatalog quests;
	private final Supplier<List<NuzlockeChunk>> chunkSource;
	private final IntPredicate freeRegion;
	private final Runnable replan;
	private final JButton copy = new JButton("Copy my real account");
	private List<NuzlockeChunk> pickerChunks;
	private Supplier<PlayerState> realSource = PlayerState::freshAccount;

	private final JLabel status = new JLabel("Not simulating. Pick a starting point.");
	private final JPanel editors = new JPanel();
	private boolean syncing;

	private ChunkPickerModel picker;
	private final JTextField chunkSearch = new JTextField();
	private final JComboBox<String> areaCombo = new JComboBox<>();
	private final JPanel chunkList = new JPanel();
	private final JLabel chunkCount = new JLabel(" ");

	private final Map<String, JSpinner> levelSpinners = new TreeMap<>();
	private final JSpinner maxHitSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 999, 1));
	private final JSpinner taskPointsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1000000, 1));

	private final JTextField questSearch = new JTextField();
	private final JPanel questList = new JPanel();
	private final List<QuestModel> sortedQuests = new ArrayList<>();

	private final JTextField itemSearch = new JTextField();
	private final DefaultListModel<ItemDef> itemResults = new DefaultListModel<>();
	private final JList<ItemDef> itemResultList = new JList<>(itemResults);
	private final JSpinner itemQty = new JSpinner(new SpinnerNumberModel(1, 1, 2000000000, 1));
	private final DefaultListModel<String> ownedModel = new DefaultListModel<>();
	private final List<Integer> ownedIds = new ArrayList<>();
	private final JList<String> ownedList = new JList<>(ownedModel);

	public SimulatePanel(SimulationSession session, GpsData data, QuestCatalog quests,
		Supplier<List<NuzlockeChunk>> chunkSource, IntPredicate freeRegion, Runnable replan)
	{
		super(new BorderLayout());
		this.session = session;
		this.data = data;
		this.quests = quests;
		this.chunkSource = chunkSource;
		this.freeRegion = freeRegion;
		this.replan = replan;
		setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		JPanel starts = new JPanel(new GridLayout(0, 1, 0, 2));
		JButton fresh = new JButton("Brand-new account");
		fresh.addActionListener(e -> startFresh());
		copy.addActionListener(e -> startFromReal());
		JButton stop = new JButton("Stop simulating");
		stop.addActionListener(e ->
		{
			session.stop();
			updateStatus();
			// The listener only re-plans on auto; a stale simulated plan must not linger.
			replan.run();
		});
		starts.add(fresh);
		starts.add(copy);
		starts.add(stop);
		top.add(starts);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		top.add(status);
		add(top, BorderLayout.NORTH);

		editors.setLayout(new BoxLayout(editors, BoxLayout.Y_AXIS));
		editors.setVisible(false);
		buildChunkSection();
		buildLevelSection();
		buildQuestSection();
		buildItemSection();
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(editors, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
	}

	/** Where "Copy my real account" reads the real state from. EDT only. */
	public void setRealSource(Supplier<PlayerState> real)
	{
		this.realSource = real;
	}

	/** Enables "Copy my real account" only while a real character is logged in (else it would copy a bogus state). */
	public void setRealAvailable(boolean available)
	{
		copy.setEnabled(available);
		copy.setToolTipText(available ? null : "Log in to copy your real account");
	}

	private void startFresh()
	{
		ensurePicker();
		session.startFresh(SimulationSession.startingRegions(chunkSource.get(), freeRegion));
		afterStart();
	}

	private void startFromReal()
	{
		ensurePicker();
		if (!copy.isEnabled())
		{
			return;
		}
		session.startFromReal(realSource.get());
		afterStart();
	}

	private void afterStart()
	{
		syncFromSession();
		updateStatus();
		replan.run();
	}

	private void updateStatus()
	{
		status.setText(session.isActive() ? "Simulating." : "Not simulating. Pick a starting point.");
		editors.setVisible(session.isActive());
	}

	private void ensurePicker()
	{
		List<NuzlockeChunk> chunks = chunkSource.get();
		if (picker != null && chunks == pickerChunks)
		{
			return;
		}
		pickerChunks = chunks;
		picker = ChunkPickerModel.fromChunks(chunks);
		areaCombo.removeAllItems();
		areaCombo.addItem(ChunkPickerModel.ALL_AREAS);
		for (String a : picker.areas())
		{
			areaCombo.addItem(a);
		}
	}

	/** Reloads every control from the session without echoing the values back as edits. */
	private void syncFromSession()
	{
		PlayerState v = session.view();
		if (v == null)
		{
			return;
		}
		syncing = true;
		try
		{
			for (Map.Entry<String, JSpinner> e : levelSpinners.entrySet())
			{
				e.getValue().setValue(v.getLevel(e.getKey()));
			}
			maxHitSpinner.setValue(v.getMaxHit());
			taskPointsSpinner.setValue(v.getTaskPoints());
			picker.selectAreaFrom(v.getUnlockedChunks());
			renderChunks();
			renderQuests();
			renderOwned();
		}
		finally
		{
			syncing = false;
		}
	}

	private void buildChunkSection()
	{
		JPanel p = section("Unlocked chunks");
		chunkSearch.getDocument().addDocumentListener(listener(this::renderChunks));
		areaCombo.addActionListener(e -> renderChunks());
		p.add(chunkSearch);
		p.add(areaCombo);
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton all = new JButton("Select visible");
		all.addActionListener(e -> setVisibleChunks(true));
		JButton none = new JButton("Clear visible");
		none.addActionListener(e -> setVisibleChunks(false));
		row.add(all);
		row.add(none);
		p.add(row);
		p.add(chunkCount);
		chunkList.setLayout(new BoxLayout(chunkList, BoxLayout.Y_AXIS));
		p.add(chunkList);
		editors.add(p);
	}

	private List<ChunkEntry> visibleChunks()
	{
		return picker.filter(chunkSearch.getText(), (String) areaCombo.getSelectedItem());
	}

	private void setVisibleChunks(boolean on)
	{
		if (picker == null || !session.isActive())
		{
			return;
		}
		List<ChunkEntry> visible = visibleChunks();
		picker.setAll(visible, on);
		for (ChunkEntry e : visible)
		{
			session.setChunk(e.getRegionId(), on);
		}
		renderChunks();
	}

	private void renderChunks()
	{
		if (picker == null)
		{
			return;
		}
		chunkList.removeAll();
		List<ChunkEntry> visible = visibleChunks();
		int shown = 0;
		for (ChunkEntry entry : visible)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			JCheckBox box = new JCheckBox(entry.getName(), picker.selected().contains(entry.getRegionId()));
			box.setToolTipText(entry.getRegionId() + (entry.getArea() == null ? "" : " - " + entry.getArea()));
			box.addActionListener(e ->
			{
				if (!syncing && session.isActive())
				{
					picker.set(entry.getRegionId(), box.isSelected());
					session.setChunk(entry.getRegionId(), box.isSelected());
				}
			});
			chunkList.add(box);
		}
		chunkCount.setText(visible.size() + " shown" + (visible.size() > MAX_ROWS ? " (first " + MAX_ROWS + " listed)" : "")
			+ ", " + picker.selected().size() + " selected");
		chunkList.revalidate();
		chunkList.repaint();
	}

	private void buildLevelSection()
	{
		JPanel p = section("Skill levels");
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 2));
		for (String skill : GpsPanel.SKILLS)
		{
			JSpinner sp = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
			sp.setName(skill);
			sp.addChangeListener(e ->
			{
				if (!syncing)
				{
					session.setLevel(skill, (Integer) sp.getValue());
				}
			});
			levelSpinners.put(skill, sp);
			grid.add(new JLabel(skill.substring(0, 1) + skill.substring(1).toLowerCase(Locale.ROOT)));
			grid.add(sp);
		}
		p.add(grid);

		JPanel misc = new JPanel(new GridLayout(0, 2, 4, 2));
		maxHitSpinner.addChangeListener(e ->
		{
			if (!syncing)
			{
				session.setMaxHit((Integer) maxHitSpinner.getValue());
			}
		});
		taskPointsSpinner.addChangeListener(e ->
		{
			if (!syncing)
			{
				session.setTaskPoints((Integer) taskPointsSpinner.getValue());
			}
		});
		misc.add(new JLabel("Max hit"));
		misc.add(maxHitSpinner);
		misc.add(new JLabel("Task points"));
		misc.add(taskPointsSpinner);
		p.add(misc);
		editors.add(p);
	}

	private void buildQuestSection()
	{
		JPanel p = section("Completed quests");
		if (quests != null)
		{
			sortedQuests.addAll(quests.all());
			sortedQuests.sort(Comparator.comparing(q -> label(q).toLowerCase(Locale.ROOT)));
		}
		questSearch.getDocument().addDocumentListener(listener(this::renderQuests));
		p.add(questSearch);
		questList.setLayout(new BoxLayout(questList, BoxLayout.Y_AXIS));
		p.add(questList);
		editors.add(p);
	}

	private static String label(QuestModel q)
	{
		return q.getName() == null ? q.getId() : q.getName();
	}

	private void renderQuests()
	{
		PlayerState v = session.view();
		questList.removeAll();
		if (v != null)
		{
			String needle = questSearch.getText() == null ? "" : questSearch.getText().trim().toLowerCase(Locale.ROOT);
			int shown = 0;
			int total = 0;
			for (QuestModel q : sortedQuests)
			{
				if (!needle.isEmpty() && !label(q).toLowerCase(Locale.ROOT).contains(needle))
				{
					continue;
				}
				total++;
				if (shown >= MAX_ROWS)
				{
					continue;
				}
				shown++;
				JCheckBox box = new JCheckBox(label(q), v.hasQuest(q.getId()));
				box.addActionListener(e ->
				{
					if (!syncing)
					{
						session.toggleQuest(q.getId());
					}
				});
				questList.add(box);
			}
			if (total > shown)
			{
				questList.add(new JLabel((total - shown) + " more (search to narrow)"));
			}
		}
		questList.revalidate();
		questList.repaint();
	}

	private void buildItemSection()
	{
		JPanel p = section("Items");
		boolean hasItems = data != null && !data.getItems().isEmpty();
		JButton add = new JButton("Add");
		JButton remove = new JButton("Remove selected");
		if (!hasItems)
		{
			String tip = "Needs GPS data";
			for (Component c : new Component[]{itemSearch, itemResultList, itemQty, add})
			{
				c.setEnabled(false);
				((javax.swing.JComponent) c).setToolTipText(tip);
			}
		}
		itemSearch.getDocument().addDocumentListener(listener(this::searchItems));
		itemResultList.setVisibleRowCount(5);
		itemResultList.setCellRenderer((list, value, index, sel, focus) ->
		{
			JLabel l = new JLabel(value.getName() + " (" + value.getId() + ")");
			l.setOpaque(true);
			l.setBackground(sel ? ColorScheme.BRAND_ORANGE : list.getBackground());
			l.setForeground(sel ? ColorScheme.DARKER_GRAY_COLOR : list.getForeground());
			return l;
		});
		add.addActionListener(e ->
		{
			ItemDef def = itemResultList.getSelectedValue();
			if (def != null && session.isActive())
			{
				session.setItem(def.getId(), (Integer) itemQty.getValue());
				renderOwned();
			}
		});
		remove.addActionListener(e ->
		{
			int i = ownedList.getSelectedIndex();
			if (i >= 0 && i < ownedIds.size() && session.isActive())
			{
				session.setItem(ownedIds.get(i), 0);
				renderOwned();
			}
		});
		p.add(itemSearch);
		p.add(new JScrollPane(itemResultList));
		JPanel qty = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		qty.add(new JLabel("Qty"));
		qty.add(itemQty);
		qty.add(add);
		p.add(qty);
		ownedList.setVisibleRowCount(5);
		p.add(new JLabel("Owned"));
		p.add(new JScrollPane(ownedList));
		p.add(remove);
		editors.add(p);
	}

	private void searchItems()
	{
		itemResults.clear();
		if (data == null)
		{
			return;
		}
		String needle = itemSearch.getText() == null ? "" : itemSearch.getText().trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty())
		{
			return;
		}
		int n = 0;
		for (ItemDef d : data.getItems())
		{
			if (d.getName() != null && d.getName().toLowerCase(Locale.ROOT).contains(needle))
			{
				itemResults.addElement(d);
				if (++n >= MAX_ITEM_RESULTS)
				{
					break;
				}
			}
		}
	}

	private void renderOwned()
	{
		ownedModel.clear();
		ownedIds.clear();
		PlayerState v = session.view();
		if (v == null)
		{
			return;
		}
		Map<Integer, String> names = new TreeMap<>();
		if (data != null)
		{
			for (ItemDef d : data.getItems())
			{
				if (v.getItems().containsKey(d.getId()))
				{
					names.put(d.getId(), d.getName());
				}
			}
		}
		for (Map.Entry<Integer, Integer> e : new TreeMap<>(v.getItems()).entrySet())
		{
			ownedIds.add(e.getKey());
			String name = names.get(e.getKey());
			ownedModel.addElement((name == null ? "Item " + e.getKey() : name) + " x" + e.getValue());
		}
	}

	private static JPanel section(String title)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createTitledBorder(title));
		return p;
	}

	private static DocumentListener listener(Runnable r)
	{
		return new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				r.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				r.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				r.run();
			}
		};
	}
}
