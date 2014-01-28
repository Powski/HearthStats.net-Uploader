package net.hearthstats;

import java.net.URI;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import com.boxysystems.jgoogleanalytics.FocusPoint;
import com.boxysystems.jgoogleanalytics.JGoogleAnalyticsTracker;
import com.sun.org.apache.bcel.internal.generic.NEWARRAY;

@SuppressWarnings("serial")
public class Monitor extends JFrame implements Observer {

	protected API _api = new API();
	protected HearthstoneAnalyzer _analyzer = new HearthstoneAnalyzer();
	protected ProgramHelper _hsHelper = new ProgramHelper("Hearthstone", "Hearthstone.exe");
	protected int _pollingIntervalInMs = 100;
	protected boolean _hearthstoneDetected;
	protected JGoogleAnalyticsTracker _analytics;
	
	public void start() throws IOException {
		
		if(Config.analyticsEnabled()) {
			_analytics = new JGoogleAnalyticsTracker("HearthStats.net Uploader", Config.getVersion(), "UA-45442103-3");
			_analytics.trackAsynchronously(new FocusPoint("AppStart"));
		}
		
		_checkForUpdates();
		
		Image icon = new ImageIcon("images/icon.png").getImage();

		f.setIconImage(icon);
		f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		f.setLocation(20, 20);
		f.setSize(600, 700);
		f.setVisible(true);
		f.setTitle("HearthStats.net Uploader");
		
		_api.addObserver(this);
		
		_analyzer.addObserver(this);
		
		// prompt user to change userkey
		if(Config.getUserKey().matches("your_userkey_here"))
			JOptionPane.showMessageDialog(null, "HearthStats.net Uploader Error:\n\nYou need to change [userkey] in config.ini\n\nSee readme.md for instructions");
		
		_pollHearthstone();

	}

	private void _checkForUpdates() {
		if(Config.checkForUpdates()) {
			try {
				URL url = new URL("https://raw.github.com/JeromeDane/HearthStats.net-Uploader/master/version");
				BufferedReader reader = null;
				String availableVersion = "";
				try {
				    reader = new BufferedReader(new InputStreamReader(url.openStream(), "UTF-8"));
				    
				    for (String line; (line = reader.readLine()) != null;) {
				    	availableVersion += line;
				    }
				} finally {
				    if (reader != null) try { reader.close(); } catch (IOException ignore) {}
				}
				if(!availableVersion.matches(Config.getVersion())) {
					if(Config.alertUpdates()) {
						JOptionPane.showMessageDialog(null, "HearthStats.net Uploader Update Available: v" + availableVersion + "\n\n" +
								"A new version of HearthStats.net is available.\n" +
								"You are currently using v" + Config.getVersion() + ".\n\n" +
								"You will now be taken to the download page.\n\n" +
								"Edit config.ini to disable future update checks.");				
					}
					// Create Desktop object
					 Desktop d = Desktop.getDesktop();

					 // Browse a URL, say google.com
					 d.browse(new URI("https://github.com/JeromeDane/HearthStats.net-Uploader/releases"));
				}
			} catch(Exception e) {
				_notify("Update Checking Error", "Unable to determine the latest available version");
			}
		}
	}

	protected ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);

	protected JFrame f = new JFrame();

	protected boolean _drawPaneAdded = false;

	protected BufferedImage image;

	protected JPanel _drawPane = new JPanel() {
		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			g.drawImage(image, 0, 0, null);
		}
	};

	protected NotificationQueue _notificationQueue = new NotificationQueue();

	protected void _notify(String header) {
		_notify(header, "");
	}

	protected void _notify(String header, String message) {
		_notificationQueue.add(new net.hearthstats.Notification(header, message));

	}

	protected void _updateTitle() {
		String title = "HearthStats.net Uploader";
		if (_hearthstoneDetected) {
			if (_analyzer.getScreen() != null) {
				title += " - " + _analyzer.getScreen();
				if (_analyzer.getScreen() == "Play" && _analyzer.getMode() != null) {
					title += " " + _analyzer.getMode();
				}
				if (_analyzer.getScreen() == "Finding Opponent") {
					if (_analyzer.getMode() != null) {
						title += " for " + _analyzer.getMode() + " Game";
					}
				}
				if (_analyzer.getScreen() == "Match Start" || _analyzer.getScreen() == "Playing") {
					title += " " + (_analyzer.getMode() == null ? "[undetected]" : _analyzer.getMode());
					title += " " + (_analyzer.getCoin() ? "" : "No ") + "Coin";
					title += " " + (_analyzer.getYourClass() == null ? "[undetected]" : _analyzer.getYourClass());
					title += " VS. " + (_analyzer.getOpponentClass() == null ? "[undetected]" : _analyzer.getOpponentClass());
				}
			}
		} else {
			title += " - Waiting for Hearthstone ";
			title += Math.random() > 0.33 ? ".." : "...";
			f.setSize(600, 200);
		}
		f.setTitle(title);
	}

	protected void _updateImageFrame() {
		if (!_drawPaneAdded) {
			f.add(_drawPane);
		}
		if (image.getWidth() >= 1024) {
			f.setSize(image.getWidth(), image.getHeight());
		}
		_drawPane.repaint();
		f.invalidate();
		f.validate();
		f.repaint();
	}

	protected void _submitMatchResult() throws IOException {
		HearthstoneMatch hsMatch = new HearthstoneMatch();
		hsMatch.setMode(_analyzer.getMode());
		hsMatch.setUserClass(_analyzer.getYourClass());
		hsMatch.setDeckSlot(_analyzer.getDeckSlot());
		hsMatch.setOpponentClass(_analyzer.getOpponentClass());
		hsMatch.setCoin(_analyzer.getCoin());
		hsMatch.setResult(_analyzer.getResult());
		
		// check for new arena run
		if(hsMatch.getMode() == "Arena" && _analyzer.isNewArena()) {
			ArenaRun run = new ArenaRun();
			run.setUserClass(hsMatch.getUserClass());
			_notify("Creating new arena run");
			_api.createArenaRun(run);
			_analyzer.setIsNewArena(false);
		}
		
		String header = "Submitting match result";
		String message = hsMatch.toString(); 
		_notify(header, message);

		if(Config.analyticsEnabled()) {
			_analytics.trackAsynchronously(new FocusPoint("Submit" + hsMatch.getMode() + "Match"));
		}
		
		_api.createMatch(hsMatch);
	}
	
	protected void _handleHearthstoneFound() throws AWTException {
		
		// mark hearthstone found if necessary
		if (_hearthstoneDetected != true) {
			_hearthstoneDetected = true;
			_notify("Hearthstone found");
		}
		
		// grab the image from Hearthstone
		image = _hsHelper.getScreenCapture();
		
		// detect image stats 
		if (image.getWidth() >= 1024)
			_analyzer.analyze(image);
		
		//_updateImageFrame();
	}
	
	protected void _handleHearthstoneNotFound() {
		
		// mark hearthstone not found if necessary
		if (_hearthstoneDetected) {
			_hearthstoneDetected = false;
			_notify("Hearthstone closed");
			
			f.getContentPane().removeAll();	// empty out the content pane
			_drawPaneAdded = false;
		}
	}
	
	protected void _pollHearthstone() {
		scheduledExecutorService.schedule(new Callable<Object>() {
			public Object call() throws Exception {
				
				if (_hsHelper.foundProgram())
					_handleHearthstoneFound();
				else
					_handleHearthstoneNotFound();
				
				_updateTitle();
				
				_pollHearthstone();		// repeat the process
				
				return "";
			}
		}, _pollingIntervalInMs, TimeUnit.MILLISECONDS);
	}

	protected void _handleAnalyzerEvent(Object changed) throws IOException {
		switch(changed.toString()) {
			case "arenaEnd":
				_notify("End of Arena Run Detected");
				_api.endCurrentArenaRun();
				break;
			case "coin":
				_notify("Coin Detected");
				break;
			case "deckSlot":
				_notify("Deck Slot " + _analyzer.getDeckSlot() + " Detected");
				break;
			case "mode":
				_notify(_analyzer.getMode() + " Mode Detected");
				break;
			case "newArena":
				if(_analyzer.isNewArena())
					_notify("New Arena Run Detected");
				break;
			case "opponentClass":
				_notify("Playing vs " + _analyzer.getOpponentClass());
				break;
			case "result":
				_notify(_analyzer.getResult() + " Detected");
				_submitMatchResult();
				break;
			case "screen":
				if(_analyzer.getScreen() != "Result")
					_notify(_analyzer.getScreen() + " Screen Detected");
				break;
			case "yourClass":
				_notify("Playing as " + _analyzer.getYourClass());
				break;
		}
	}
	
	
	protected void _handleApiEvent(Object changed) {
		switch(changed.toString()) {
			case "error":
				_notify("API Error", _api.getMessage());
				break;
			case "result":
				_notify("API Result", _api.getMessage());
				break;
		}
	}
	
	@Override
	public void update(Observable dispatcher, Object changed) {
		if(dispatcher.getClass().toString().matches(".*HearthstoneAnalyzer"))
			try {
				_handleAnalyzerEvent(changed);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		if(dispatcher.getClass().toString().matches(".*API"))
			_handleApiEvent(changed);
	}


}
