package fiji.scripting;

import com.sun.jdi.connect.VMStartException;

import common.RefreshScripts;

import fiji.scripting.completion.ClassCompletionProvider;
import fiji.scripting.completion.DefaultProvider;

import ij.IJ;
import ij.Prefs;
import ij.WindowManager;

import ij.gui.GenericDialog;

import ij.io.OpenDialog;
import ij.io.SaveDialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.awt.image.BufferedImage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import java.util.concurrent.ThreadPoolExecutor;

import javax.imageio.ImageIO;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextArea;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.IconGroup;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.RecordableTextAction;
import org.fife.ui.rtextarea.ToolTipSupplier;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

public class TextEditor extends JFrame implements ActionListener,
		DocumentListener {
	File file;
	RSyntaxTextArea textArea;
	JTextArea screen;
	JMenuItem new_file, open, save, saveas, compileAndRun, debug, quit,
		  undo, redo, cut, copy, paste, find, replace, selectAll,
		  autocomplete, resume, terminate, kill, gotoLine;
	AutoCompletion autocomp;
	Languages.Language currentLanguage;
	ClassCompletionProvider provider;
	FindAndReplaceDialog findDialog;
	StartDebugging debugging;
	Gutter gutter;
	IconGroup iconGroup;

	int modifyCount;
	boolean undoInProgress, redoInProgress;

	public TextEditor(String path1) {
		super("Script Editor");
		WindowManager.addWindow(this);
		JPanel cp = new JPanel(new BorderLayout());
		textArea = new RSyntaxTextArea() {
			public void undoLastAction() {
				undoInProgress = true;
				super.undoLastAction();
				undoInProgress = false;
			}

			public void redoLastAction() {
				redoInProgress = true;
				super.redoLastAction();
				redoInProgress = false;
			}
		};
		textArea.setTabSize(8);
		textArea.getActionMap().put(DefaultEditorKit
			.nextWordAction, wordMovement(+1, false));
		textArea.getActionMap().put(DefaultEditorKit
			.selectionNextWordAction, wordMovement(+1, true));
		textArea.getActionMap().put(DefaultEditorKit
			.previousWordAction, wordMovement(-1, false));
		textArea.getActionMap().put(DefaultEditorKit
			.selectionPreviousWordAction, wordMovement(-1, true));
		provider = new ClassCompletionProvider(new DefaultProvider(),
				textArea, null);
		autocomp = new AutoCompletion(provider);

		autocomp.setListCellRenderer(new CCellRenderer());
		autocomp.setShowDescWindow(true);
		autocomp.setParameterAssistanceEnabled(true);
		autocomp.install(textArea);
		textArea.setToolTipSupplier((ToolTipSupplier)provider);
		ToolTipManager.sharedInstance().registerComponent(textArea);
		textArea.getDocument().addDocumentListener(this);
		RTextScrollPane sp = new RTextScrollPane(textArea);
		sp.setPreferredSize(new Dimension(600, 350));
		sp.setIconRowHeaderEnabled(true);
		gutter = sp.getGutter();
		iconGroup = new IconGroup("bullets", "images/", null, "png", null);
		gutter.setBookmarkIcon(iconGroup.getIcon("var"));
		gutter.setBookmarkingEnabled(true);
		screen = new JTextArea();
		screen.setEditable(false);
		screen.setLineWrap(true);
		Font font = new Font("Courier", Font.PLAIN, 12);
		screen.setFont(font);
		JScrollPane scroll = new JScrollPane(screen);
		scroll.setPreferredSize(new Dimension(600, 80));
		JSplitPane panel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sp, scroll);
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		panel.setResizeWeight(350.0 / 430.0);
		setContentPane(panel);

		int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
		JMenuBar mbar = new JMenuBar();
		setJMenuBar(mbar);

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		new_file = addToMenu(file, "New",  KeyEvent.VK_N, ctrl);
		open = addToMenu(file, "Open...",  KeyEvent.VK_O, ctrl);
		save = addToMenu(file, "Save", KeyEvent.VK_S, ctrl);
		saveas = addToMenu(file, "Save as...", 0, 0);
		file.addSeparator();
		quit = addToMenu(file, "Close Editor", KeyEvent.VK_W, ctrl);

		mbar.add(file);

		JMenu edit = new JMenu("Edit");
		edit.setMnemonic(KeyEvent.VK_E);
		undo = addToMenu(edit, "Undo", KeyEvent.VK_Z, ctrl);
		redo = addToMenu(edit, "Redo", KeyEvent.VK_Y, ctrl);
		edit.addSeparator();
		selectAll = addToMenu(edit, "Select All", KeyEvent.VK_A, ctrl);
		cut = addToMenu(edit, "Cut", KeyEvent.VK_X, ctrl);
		copy = addToMenu(edit, "Copy", KeyEvent.VK_C, ctrl);
		paste = addToMenu(edit, "Paste", KeyEvent.VK_V, ctrl);
		edit.addSeparator();
		find = addToMenu(edit, "Find...", KeyEvent.VK_F, ctrl);
		replace = addToMenu(edit, "Find and Replace...", KeyEvent.VK_H, ctrl);
		gotoLine = addToMenu(edit, "Goto line...", KeyEvent.VK_G, ctrl);
		mbar.add(edit);

		JMenu options = new JMenu("Options");
		options.setMnemonic(KeyEvent.VK_O);
		autocomplete = addToMenu(options, "Autocomplete", KeyEvent.VK_SPACE, ctrl);
		options.addSeparator();

		mbar.add(options);

		JMenu languages = new JMenu("Language");
		languages.setMnemonic(KeyEvent.VK_L);
		ButtonGroup group = new ButtonGroup();
		for (final Languages.Language language :
		                Languages.getInstance().languages) {
			JRadioButtonMenuItem item =
			        new JRadioButtonMenuItem(language.menuLabel);
			if (language.shortCut != 0)
				item.setMnemonic(language.shortCut);
			item.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					setLanguage(language);
				}
			});

			group.add(item);
			languages.add(item);
			language.item = item;
		}
		mbar.add(languages);

		JMenu run = new JMenu("Run");
		run.setMnemonic(KeyEvent.VK_R);
		// TODO: allow outside-of-plugins/ sources

		compileAndRun = addToMenu(run, "Compile and Run",
				KeyEvent.VK_R, ctrl);

		run.addSeparator();
		debug = addToMenu(run, "Start Debugging", KeyEvent.VK_D, ctrl);

		// for Eclipse and MS Visual Studio lovers
		addAccelerator(compileAndRun, KeyEvent.VK_F11, 0);
		addAccelerator(compileAndRun, KeyEvent.VK_F5, 0);
		addAccelerator(debug, KeyEvent.VK_F11, ctrl);
		addAccelerator(debug, KeyEvent.VK_F5,
				ActionEvent.SHIFT_MASK);

		mbar.add(run);

		run.addSeparator();

		kill = addToMenu(run, "Kill running script...", 0, 0);
		kill.setEnabled(false);

		JMenu breakpoints = new JMenu("Breakpoints");
		breakpoints.setMnemonic(KeyEvent.VK_B);
		resume = addToMenu(breakpoints, "Resume", 0, 0);
		terminate = addToMenu(breakpoints, "Terminate", 0, 0);
		mbar.add(breakpoints);

		pack();
		getToolkit().setDynamicLayout(true);            //added to accomodate the autocomplete part
		findDialog = new FindAndReplaceDialog(this, textArea);

		setLanguage(null);
		setTitle();

		setLocationRelativeTo(null); // center on screen
		if (path1 != null && !path1.equals(""))
			open(path1);

		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				if (!handleUnsavedChanges())
					return;
				WindowManager.removeWindow(TextEditor.this);
				dispose();
			}
		});
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
	}

	public JMenuItem addToMenu(JMenu menu, String menuEntry,
			int key, int modifiers) {
		JMenuItem item = new JMenuItem(menuEntry);
		menu.add(item);
		if (key != 0)
			item.setAccelerator(KeyStroke.getKeyStroke(key,
						modifiers));
		item.addActionListener(this);
		return item;
	}

	public void addAccelerator(final JMenuItem component,
			int key, int modifiers) {
		textArea.getInputMap().put(KeyStroke.getKeyStroke(key,
					modifiers), component);
		if (textArea.getActionMap().get(component) != null)
			return;
		textArea.getActionMap().put(component,
				new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				if (!component.isEnabled())
					return;
				ActionEvent event = new ActionEvent(component,
					0, "Accelerator");
				TextEditor.this.actionPerformed(event);
			}
		});
	}

	public void createNewDocument() {
		open(null);
	}

	public boolean handleUnsavedChanges() {
		if (!fileChanged())
			return true;

		switch (JOptionPane.showConfirmDialog(this,
				"Do you want to save changes?")) {
		case JOptionPane.NO_OPTION:
			return true;
		case JOptionPane.YES_OPTION:
			if (save())
				return true;
		}

		return false;
	}

	public void actionPerformed(ActionEvent ae) {
		final Object source = ae.getSource();
		if (source == new_file) {
			if (!handleUnsavedChanges())
				return;
			createNewDocument();
		}
		else if (source == open) {
			if (!handleUnsavedChanges())
				return;

			OpenDialog dialog = new OpenDialog("Open..", "");
			String name = dialog.getFileName();
			if (name != null)
				open(dialog.getDirectory() + name);
			return;
		}
		else if (source == save)
			save();
		else if (source == saveas)
			saveAs();
		else if (source == compileAndRun)
			runText();
		else if (source == debug) {
			if (currentLanguage == null ||
					!currentLanguage.isDebuggable()) {
				error("No debug support for this language");
				return;
			}
			BreakpointManager manager = new BreakpointManager(gutter, textArea, iconGroup);
			debugging = new StartDebugging(file.getPath(), manager.findBreakpointsLineNumber());

			try {
				System.out.println(debugging.startDebugging().exitValue());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		else if (source == kill)
			chooseTaskToKill();
		else if (source == quit)
			processWindowEvent( new WindowEvent(this, WindowEvent.WINDOW_CLOSING) );
		else if (source == cut)
			textArea.cut();
		else if (source == copy)
			textArea.copy();
		else if (source == paste)
			textArea.paste();
		else if (source == undo)
			textArea.undoLastAction();
		else if (source == redo)
			textArea.redoLastAction();
		else if (source == find)
			findOrReplace(false);
		else if (source == replace)
			findOrReplace(true);
		else if (source == gotoLine)
			gotoLine();
		else if (source == selectAll) {
			textArea.setCaretPosition(0);
			textArea.moveCaretPosition(textArea.getDocument().getLength());
		}
		else if (source == autocomplete) {
			try {
				autocomp.doCompletion();
			} catch (Exception e) {}
		}
		else if (source == resume)
			debugging.resumeVM();
		else if (source == terminate) {
			// TODO not implemented
		}

	}

	protected RSyntaxDocument getDocument() {
		return (RSyntaxDocument)textArea.getDocument();
	}

	public void findOrReplace(boolean replace) {
		findDialog.setLocationRelativeTo(this);
		findDialog.show(replace);
	}

	public void gotoLine() {
		String line = JOptionPane.showInputDialog(this, "Line:",
			"Goto line...", JOptionPane.QUESTION_MESSAGE);
		try {
			gotoLine(Integer.parseInt(line));
		} catch (BadLocationException e) {
			error("Line number out of range: " + line);
		} catch (NumberFormatException e) {
			error("Invalid line number: " + line);
		}
	}

	public void gotoLine(int line) throws BadLocationException {
		textArea.setCaretPosition(textArea.getLineStartOffset(line-1));
	}

	public void open(String path) {
		if (path == null) {
			file = null;
			textArea.setText("");
		}
		else try {
			file = new File(path);
			if (!file.exists()) {
				modifyCount = Integer.MIN_VALUE;
				setFileName(file);
				return;
			}
			textArea.read(new BufferedReader(new FileReader(file)),
				null);
		} catch (Exception e) {
			e.printStackTrace();
			error("The file '" + path + "' was not found.");
			return;
		}
		textArea.discardAllEdits();
		modifyCount = 0;
		setFileName(file);
	}

	public boolean saveAs() {
		SaveDialog sd = new SaveDialog("Save as ", getFileName() , "");
		String name = sd.getFileName();
		if (name == null)
			return false;

		String path = sd.getDirectory() + name;
		return saveAs(path, true);
	}

	public void saveAs(String path) {
		saveAs(path, true);
	}

	public boolean saveAs(String path, boolean askBeforeReplacing) {
		file = new File(path);
		if (file.exists() && !askBeforeReplacing &&
				JOptionPane.showConfirmDialog(this,
					"Do you want to replace " + path + "?",
					"Replace " + path + "?",
					JOptionPane.YES_NO_OPTION)
				!= JOptionPane.YES_OPTION)
			return false;
		if (!write(file))
			return false;
		setFileName(file);
		return true;
	}

	public boolean save() {
		if (file == null)
			return saveAs();
		if (!write(file))
			return false;
		setTitle();
		return true;
	}

	public boolean write(File file) {
		try {
			BufferedWriter outFile =
				new BufferedWriter(new FileWriter(file));
			outFile.write(textArea.getText());
			outFile.close();
			modifyCount = 0;
			return true;
		} catch (IOException e) {
			error("Could not save " + file.getName());
			e.printStackTrace();
			return false;
		}
	}

	public static String getExtension(String fileName) {
		int dot = fileName.lastIndexOf(".");
		return dot < 0 ?  "" : fileName.substring(dot);
	}

	private void setLanguageByExtension(String extension) {
		setLanguage(Languages.get(extension));
	}

	protected void setLanguage(Languages.Language language) {
		if (language == null)
			language = Languages.get("");

		if (file != null) {
			String name = file.getName();
			if (!name.endsWith(language.extension) &&
					currentLanguage != null) {
				String ext = currentLanguage.extension;
				if (name.endsWith(ext))
					name = name.substring(0, name.length()
							- ext.length());
				file = new File(file.getParentFile(),
						name + language.extension);
				modifyCount = Integer.MIN_VALUE;
			}
		}
		currentLanguage = language;
		setTitle();

		if (!language.item.isSelected())
			language.item.setSelected(true);

		compileAndRun.setLabel(language.isCompileable() ?
			"Compile and Run" : "Run");
		compileAndRun.setEnabled(language.isRunnable());
		debug.setEnabled(language.isDebuggable());

		provider.setProviderLanguage(language.menuLabel);

		// TODO: these should go to upstream RSyntaxTextArea
		if (language.syntaxStyle != null)
			textArea.setSyntaxEditingStyle(language.syntaxStyle);
		else if (language.extension.equals(".clj"))
			getDocument().setSyntaxStyle(new ClojureTokenMaker());
		else if (language.extension.equals(".m"))
			getDocument().setSyntaxStyle(new MatlabTokenMaker());
	}

	public void setFileName(File file) {
		setTitle();
		if (file != null)
			setLanguageByExtension(getExtension(file.getName()));
	}

	protected String getFileName() {
		return file == null ?
			"New_" + currentLanguage.extension : file.getName();
	}

	private synchronized void setTitle() {
		String title = (fileChanged() ? "*" : "") + getFileName()
			+ (executingTasks.isEmpty() ? "" : " (Running)");
		setTitle(title);
	}

	/** Using a Vector to benefit from all its methods being synchronzed. */
	private ArrayList<Executer> executingTasks = new ArrayList<Executer>();

	/** Generic Thread that keeps a starting time stamp,
	 *  sets the priority to normal and starts itself. */
	private abstract class Executer extends ThreadGroup {
		JTextAreaOutputStream output;
		Executer(final JTextAreaOutputStream output) {
			super("Script Editor Run :: " + new Date().toString());
			this.output = output;
			// Store itself for later
			executingTasks.add(this);
			setTitle();
			// Enable kill menu
			kill.setEnabled(true);
			// Fork a task, as a part of this ThreadGroup
			new Thread(this, getName()) {
				{
					setPriority(Thread.NORM_PRIORITY);
					start();
				}
				public void run() {
					try {
						execute();
						// Wait until any children threads die:
						while (Executer.this.activeCount() > 1) {
							if (isInterrupted()) break;
							try {
								Thread.sleep(500);
							} catch (InterruptedException ie) {}
						}
					} catch (Throwable t) {
						t.printStackTrace();
					} finally {
						executingTasks.remove(Executer.this);
						try {
							if (null != output)
								output.shutdown();
						} catch (Exception e) {
							e.printStackTrace();
						}
						// Leave kill menu item enabled if other tasks are running
						kill.setEnabled(executingTasks.size() > 0);
						setTitle();
					}
				}
			};
		}
		
		/** The method to extend, that will do the actual work. */
		abstract void execute();

		/** Fetch a list of all threads from all thread subgroups, recursively. */
		List<Thread> getAllThreads() {
			ArrayList<Thread> threads = new ArrayList<Thread>();
			ThreadGroup[] tgs = new ThreadGroup[activeGroupCount() * 2 + 100];
			this.enumerate(tgs);
			for (ThreadGroup tg : tgs) {
				if (null == tg) continue;
				Thread[] ts = new Thread[tg.activeCount() * 2 + 100];
				tg.enumerate(ts);
				for (Thread t : ts) {
					if (null == t) continue;
					threads.add(t);
				}
			}
			return threads;
		}

		/** Totally destroy/stop all threads in this and all recursive thread subgroups. Will remove itself from the executingTasks list. */
		void obliterate() {
			try {
				// Stop printing to the screen
				if (null != output)
					output.shutdownNow();
			} catch (Exception e) {
				e.printStackTrace();
			}
			for (Thread thread : getAllThreads()) {
				try {
					thread.interrupt();
					Thread.yield(); // give it a chance
					thread.stop();
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
			executingTasks.remove(this);
			setTitle();
		}
	}

	/** Query the list of running scripts and provide a dialog to choose one and kill it. */
	public void chooseTaskToKill() {
		Executer[] executers =
			executingTasks.toArray(new Executer[0]);
		if (0 == executers.length) {
			error("\nNo tasks running!\n");
			return;
		}

		String[] names = new String[executers.length];
		for (int i = 0; i < names.length; i++)
			names[i] = executers[i].getName();

		GenericDialog gd = new GenericDialog("Kill");
		gd.addChoice("Running scripts: ",
				names, names[names.length - 1]);
		gd.addCheckbox("Kill all", false);
		gd.showDialog();
		if (gd.wasCanceled())
			return;

		Executer[] deaders = gd.getNextBoolean() ? executers :
			new Executer[] { executers[gd.getNextChoiceIndex()] };
		for (final Executer executer : deaders)
			kill(executer);
	}

	protected void kill(final Executer executer) {
		// Graceful attempt:
		executer.interrupt();
		// Give it 3 seconds. Then, stop it.
		final long now = System.currentTimeMillis();
		new Thread() {
			{ setPriority(Thread.NORM_PRIORITY); }
			public void run() {
				while (System.currentTimeMillis() - now < 3000)
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {}
				executer.obliterate();
			}
		}.start();
	}

	/** Run the text in the textArea without compiling it, only if it's not java. */
	public void runText() {
		if (currentLanguage.isCompileable()) {
			if (handleUnsavedChanges())
				runScript();
			return;
		}
		if (!currentLanguage.isRunnable()) {
			error("Select a language first!");
			// TODO guess the language, if possible.
			return;
		}

		textArea.setEditable(false);
		final JTextAreaOutputStream output = new JTextAreaOutputStream(screen);
		try {
			final RefreshScripts interpreter =
				currentLanguage.interpreter;
			interpreter.setOutputStreams(output, output);

			// Pipe current text into the runScript:
			final PipedInputStream pi = new PipedInputStream();
			final PipedOutputStream po = new PipedOutputStream(pi);
			new TextEditor.Executer(output) {
				public void execute() {
					interpreter.runScript(pi);
				}
			};
			textArea.write(new PrintWriter(po));
			po.flush();
			po.close();
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			textArea.setEditable(true);
		}
	}

	public void runScript() {
		String ext = getExtension(file.getName());
		final RefreshScripts interpreter =
		        Languages.getInstance().get(ext).interpreter;

		if (interpreter == null) {
			error("There is no interpreter for " + ext
			         + " files!");
			return;
		}

		JTextAreaOutputStream output = new JTextAreaOutputStream(screen);
		interpreter.setOutputStreams(output, output);

		new TextEditor.Executer(new JTextAreaOutputStream(screen)) {
			public void execute() {
				interpreter.runScript(file.getPath());
			}
		};
	}

	RecordableTextAction wordMovement(final int direction,
			final boolean select) {
		final String id = "WORD_MOVEMENT_" + select + direction;
		return new RecordableTextAction(id) {
			public void actionPerformedImpl(ActionEvent e,
					RTextArea textArea) {
				int pos = textArea.getCaretPosition();
				int end = direction < 0 ? 0 :
					textArea.getDocument().getLength();
				while (pos != end && !isWordChar(textArea, pos))
					pos += direction;
				while (pos != end && isWordChar(textArea, pos))
					pos += direction;
				if (select)
					textArea.moveCaretPosition(pos);
				else
					textArea.setCaretPosition(pos);
			}

			public String getMacroID() {
				return id;
			}

			boolean isWordChar(RTextArea textArea, int pos) {
				try {
					char c = textArea.getText(pos
						+ (direction < 0 ? -1 : 0), 1)
						.charAt(0);
					return c > 0x7f ||
						(c >= 'A' && c <= 'Z') ||
						(c >= 'a' && c <= 'z') ||
						(c >= '0' && c <= '9') ||
						c == '_';
				} catch (BadLocationException e) {
					return false;
				}
			}
		};
	}

	public boolean fileChanged() {
		return modifyCount != 0;
	}

	public void insertUpdate(DocumentEvent e) {
		modified();
	}

	public void removeUpdate(DocumentEvent e) {
		modified();
	}

	// triggered only by syntax highlighting
	public void changedUpdate(DocumentEvent e) { }

	protected void modified() {
		boolean update = modifyCount == 0;
		if (undoInProgress)
			modifyCount--;
		else if (redoInProgress || modifyCount >= 0)
			modifyCount++;
		else // not possible to get back to clean state
			modifyCount = Integer.MIN_VALUE;
		if (update || modifyCount == 0)
			setTitle();
	}

	protected void error(String message) {
		JOptionPane.showMessageDialog(this, message);
	}
}
// TODO: check all files for whitespace issues
