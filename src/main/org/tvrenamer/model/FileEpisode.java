// FileEpisode - represents a file on disk which is presumed to contain a single
//   episode of a TV show.
//
// This is a very mutable class.  It is initially created with just a filename,
// and then information comes streaming in.
//

package org.tvrenamer.model;

import static org.tvrenamer.model.util.Constants.*;

import org.tvrenamer.controller.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

public class FileEpisode {
    private static Logger logger = Logger.getLogger(FileEpisode.class.getName());

    private enum ParseStatus {
        UNPARSED,
        PARSED,
        BAD_PARSE
    }

    private enum SeriesStatus {
        NOT_STARTED,
        GOT_SHOW,
        UNFOUND,
        GOT_LISTINGS,
        NO_LISTINGS
    }

    private enum FileStatus {
        UNCHECKED,
        NO_FILE,
        MOVING,
        RENAMED,
        FAIL_TO_MOVE
    }

    private static final String FILE_SEPARATOR_STRING = java.io.File.separator;
    private static final long NO_FILE_SIZE = -1L;

    // This is the one final field in this class; it's the one thing that should never
    // change in a FileEpisode.
    private final String filenameSuffix;

    // These four fields reflect the information derived from the filename.  In particular,
    // filenameShow is based on the part of the filename we "guessed" represented the name
    // of the show, and which we use to query the provider.  Note that the actual show name
    // that we get back from the provider will likely differ from what we have here.
    private String filenameShow = "";
    private String filenameSeason = "";
    private String filenameEpisode = "";
    private String filenameResolution = "";

    private int seasonNum = Show.NO_SEASON;
    private int episodeNum = Show.NO_EPISODE;

    private Path path;
    private String fileNameString;
    private boolean exists = false;
    private long fileSize = NO_FILE_SIZE;


    // After we've looked up the filenameShow from the provider, we should get back an
    // actual Show object.  This is true even if the show was not found; in that case,
    // we should get an instance of a FailedShow.
    private Show actualShow = null;

    // This class represents a file on disk, with fields that indicate which episode we
    // believe it refers to, based on the filename.  The "Episode" class represents
    // information about an actual episode of a show, based on listings from the provider.
    // Once we have the listings, we should be able to map this instance to an Episode.
    private Episode actualEpisode = null;

    // This class actually figures out the proposed new name for the file, so we need
    // a link to the user preferences to know how the user wants the file renamed.
    private UserPreferences userPrefs = UserPreferences.getInstance();

    // The state of this object, not the state of the actual TV episode.
    private ParseStatus parseStatus = ParseStatus.UNPARSED;
    private SeriesStatus seriesStatus = SeriesStatus.NOT_STARTED;
    private FileStatus fileStatus = FileStatus.UNCHECKED;

    // This is the basic part of what we would rename the file to.  That is, we would
    // rename it to destinationFolder + baseForRename + filenameSuffix.
    private String baseForRename = null;

    // Initially we create the FileEpisode with nothing more than the path.
    // Other information will flow in.
    public FileEpisode(Path p) {
        fileNameString = p.getFileName().toString();
        filenameSuffix = StringUtils.getExtension(fileNameString);
        setPath(p);
    }

    public FileEpisode(String filename) {
        this(Paths.get(filename));
    }

    public String getFilenameShow() {
        return filenameShow;
    }

    public void setFilenameShow(String filenameShow) {
        this.filenameShow = filenameShow;
    }

    public int getSeasonNum() {
        return seasonNum;
    }

    public String getFilenameSeason() {
        return filenameSeason;
    }

    public void setSeasonNum(int seasonNum) {
        this.seasonNum = seasonNum;
    }

    public void setFilenameSeason(String filenameSeason) {
        this.filenameSeason = filenameSeason;
        try {
            seasonNum = Integer.parseInt(filenameSeason);
        } catch (Exception e) {
            seasonNum = Show.NO_SEASON;
        }
    }

    public int getEpisodeNum() {
        return episodeNum;
    }

    public String getFilenameEpisode() {
        return filenameEpisode;
    }

    public void setEpisodeNum(int episodeNum) {
        this.episodeNum = episodeNum;
    }

    public void setFilenameEpisode(String filenameEpisode) {
        this.filenameEpisode = filenameEpisode;
        try {
            episodeNum = Integer.parseInt(filenameEpisode);
        } catch (Exception e) {
            episodeNum = Show.NO_EPISODE;
        }
    }

    public String getFilenameResolution() {
        return filenameResolution;
    }

    public void setFilenameResolution(String filenameResolution) {
        if (filenameResolution == null) {
            this.filenameResolution = "";
        } else {
            this.filenameResolution = filenameResolution;
        }
    }

    public String getFilenameSuffix() {
        return filenameSuffix;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path p) {
        path = p;
        fileNameString = path.getFileName().toString();

        String newSuffix = StringUtils.getExtension(fileNameString);
        if (!filenameSuffix.equals(newSuffix)) {
            throw new IllegalStateException("suffix of a FileEpisode may not change!");
        }

        if (Files.exists(path)) {
            exists = true;
            try {
                fileSize = Files.size(path);
            } catch (IOException ioe) {
                logger.log(Level.WARNING, "couldn't get size of " + path, ioe);
                fileStatus = FileStatus.NO_FILE;
                fileSize = NO_FILE_SIZE;
            }
        } else {
            logger.fine("creating FileEpisode for nonexistent path, " + path);
            exists = false;
            fileStatus = FileStatus.NO_FILE;
            fileSize = NO_FILE_SIZE;
        }
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFilepath() {
        return path.toAbsolutePath().toString();
    }

    public boolean wasParsed() {
        return (parseStatus == ParseStatus.PARSED);
    }

    public boolean isReady() {
        return (actualEpisode != null);
    }

    public void setParsed() {
        parseStatus = ParseStatus.PARSED;
    }

    public void setFailToParse() {
        parseStatus = ParseStatus.BAD_PARSE;
    }

    public void setMoving() {
        fileStatus = FileStatus.MOVING;
    }

    public void setRenamed() {
        fileStatus = FileStatus.RENAMED;
    }

    public void setFailToMove() {
        fileStatus = FileStatus.FAIL_TO_MOVE;
    }

    public void setDoesNotExist() {
        fileStatus = FileStatus.NO_FILE;
    }

    public void setShow(Show show) {
        actualShow = show;
        if (actualShow instanceof FailedShow) {
            seriesStatus = SeriesStatus.UNFOUND;
        } else {
            seriesStatus = SeriesStatus.GOT_SHOW;
        }
    }

    public void listingsComplete() {
        if (actualShow == null) {
            logger.warning("error: should not get listings, do not have show!");
            seriesStatus = SeriesStatus.UNFOUND;
        } else if (actualShow instanceof FailedShow) {
            logger.warning("error: should not get listings, have a failed show!");
            seriesStatus = SeriesStatus.UNFOUND;
        } else {
            actualEpisode = actualShow.getEpisode(seasonNum, episodeNum);
            if (actualEpisode == null) {
                logger.log(Level.SEVERE, "Season #" + seasonNum + ", Episode #"
                           + episodeNum + " not found for show '"
                           + filenameShow + "'");
                seriesStatus = SeriesStatus.NO_LISTINGS;
            } else {
                // Success!!!
                seriesStatus = SeriesStatus.GOT_LISTINGS;
            }
        }
    }

    public void listingsFailed() {
        seriesStatus = SeriesStatus.NO_LISTINGS;
        if (actualShow == null) {
            logger.warning("error: should not have tried to get listings, do not have show!");
        } else if (actualShow instanceof FailedShow) {
            logger.warning("error: should not have tried to get listings, have a failed show!");
        }
    }

    /**
     * Return the name of the directory to which the file should be moved.
     *
     * We try to make sure that a term means the same thing throughout the program.
     * The "destination directory" is the *top-level* directory that the user has
     * specified we should move all the files into.  But the files don't necessarily
     * go directly into the "destination directory".  They will go into a sub-directory
     * naming the show and possibly the season.  That final directory is what we refer
     * to as the "move-to directory".
     *
     * @return the name of the directory into which this file (the Path encapsulated
     *         within this FileEpisode) should be moved
     */
    public String getMoveToDirectory() {
        String dirname = ShowStore.getShow(filenameShow).getDirName();
        String destPath = userPrefs.getDestinationDirectoryName();
        destPath = destPath + FILE_SEPARATOR_STRING + dirname;

        String seasonPrefix = userPrefs.getSeasonPrefix();
        // Defect #50: Only add the 'season #' folder if set, otherwise put files in showname root
        if (StringUtils.isNotBlank(seasonPrefix)) {
            String seasonString = userPrefs.isSeasonPrefixLeadingZero()
                ? StringUtils.zeroPadTwoDigits(seasonNum)
                : String.valueOf(seasonNum);
            destPath = destPath + FILE_SEPARATOR_STRING + seasonPrefix + seasonString;
        }
        return destPath;
    }

    /**
     * @return the new Path into which this file would be moved, based on the information
     *         we've gathered, and the user's preferences
     */
    public Path getMoveToPath() {
        if (userPrefs.isMoveEnabled()) {
            return Paths.get(getMoveToDirectory());
        } else {
            return path.toAbsolutePath().getParent();
        }
    }

    private String formatDate(LocalDate date, String format) {
        if (date == null) {
            return "";
        }
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(format);
        return dateFormat.format(date);
    }

    public String getRenamedBasename() {
        String showName;
        if (actualShow == null) {
            logger.warning("should not be renaming without an actual Show.");
            showName = filenameShow;
        } else {
            if (actualShow instanceof FailedShow) {
                logger.warning("should not be renaming with a FailedShow.");
            }
            // We can use getName() even if it was a FailedShow
            showName = actualShow.getName();
        }

        String titleString = "";
        LocalDate airDate = null;
        if (actualEpisode != null) {
            titleString = actualEpisode.getTitle();
            airDate = actualEpisode.getAirDate();
            if (airDate == null) {
                logger.log(Level.WARNING, "Episode air date not found for '" + toString() + "'");
            }
        }

        String newFilename = userPrefs.getRenameReplacementString();

        // Ensure that all special characters in the replacement are quoted
        showName = Matcher.quoteReplacement(showName);
        showName = GlobalOverrides.getInstance().getShowName(showName);

        // Make whatever modifications are required
        String episodeNumberString = StringUtils.formatDigits(episodeNum);
        String episodeNumberWithLeadingZeros = StringUtils.zeroPadThreeDigits(episodeNum);
        String episodeTitleNoSpaces = Matcher.quoteReplacement(StringUtils.makeDotTitle(titleString));
        String seasonNumberWithLeadingZero = StringUtils.zeroPadTwoDigits(seasonNum);

        titleString = Matcher.quoteReplacement(titleString);

        newFilename = newFilename.replaceAll(ReplacementToken.SHOW_NAME.getToken(), showName);
        newFilename = newFilename.replaceAll(ReplacementToken.SEASON_NUM.getToken(),
                                             String.valueOf(seasonNum));
        newFilename = newFilename.replaceAll(ReplacementToken.SEASON_NUM_LEADING_ZERO.getToken(),
                                             seasonNumberWithLeadingZero);
        newFilename = newFilename.replaceAll(ReplacementToken.EPISODE_NUM.getToken(),
                                             episodeNumberString);
        newFilename = newFilename.replaceAll(ReplacementToken.EPISODE_NUM_LEADING_ZERO.getToken(),
                                             episodeNumberWithLeadingZeros);
        newFilename = newFilename.replaceAll(ReplacementToken.EPISODE_TITLE.getToken(), titleString);
        newFilename = newFilename.replaceAll(ReplacementToken.EPISODE_TITLE_NO_SPACES.getToken(),
                                             episodeTitleNoSpaces);
        newFilename = newFilename.replaceAll(ReplacementToken.EPISODE_RESOLUTION.getToken(),
                                             filenameResolution);

        // Date and times
        newFilename = newFilename
            .replaceAll(ReplacementToken.DATE_DAY_NUM.getToken(), formatDate(airDate, "d"));
        newFilename = newFilename.replaceAll(ReplacementToken.DATE_DAY_NUMLZ.getToken(),
                                             formatDate(airDate, "dd"));
        newFilename = newFilename.replaceAll(ReplacementToken.DATE_MONTH_NUM.getToken(),
                                             formatDate(airDate, "M"));
        newFilename = newFilename.replaceAll(ReplacementToken.DATE_MONTH_NUMLZ.getToken(),
                                             formatDate(airDate, "MM"));
        newFilename = newFilename.replaceAll(ReplacementToken.DATE_YEAR_FULL.getToken(),
                                             formatDate(airDate, "yyyy"));
        newFilename = newFilename.replaceAll(ReplacementToken.DATE_YEAR_MIN.getToken(),
                                             formatDate(airDate, "yy"));

        // Note, this is an instance variable, not a local variable.
        baseForRename = StringUtils.sanitiseTitle(newFilename);

        return baseForRename;
    }

    private String getShowNamePlaceholder() {
        Show show = ShowStore.getShow(filenameShow);
        String showName = show.getName();

        return "<" + showName + ">";
    }

    /**
     * @return the new full file path (for table display) using {@link #getRenamedBasename()} and
     *          the destination directory
     */
    public String getReplacementText() {
        switch (seriesStatus) {
            case NOT_STARTED: {
                return ADDED_PLACEHOLDER_FILENAME;
            }
            case GOT_SHOW: {
                return getShowNamePlaceholder();
            }
            case UNFOUND: {
                return BROKEN_PLACEHOLDER_FILENAME;
            }
            case GOT_LISTINGS: {
                if (userPrefs.isRenameEnabled()) {
                    String newFilename = getRenamedBasename() + filenameSuffix;

                    if (userPrefs.isMoveEnabled()) {
                        return getMoveToDirectory() + FILE_SEPARATOR_STRING + newFilename;
                    } else {
                        return newFilename;
                    }
                } else if (userPrefs.isMoveEnabled()) {
                    return getMoveToDirectory() + FILE_SEPARATOR_STRING + fileNameString;
                } else {
                    // This setting doesn't make any sense, but we haven't bothered to
                    // disallow it yet.
                    return fileNameString;
                }
            }
            case NO_LISTINGS: {
                return DOWNLOADING_FAILED;
            }
            default: {
                logger.warning("internal error, seriesStatus check apparently not exhaustive: "
                               + seriesStatus);
                return BROKEN_PLACEHOLDER_FILENAME;
            }
        }
    }

    @Override
    public String toString() {
        return "FileEpisode { title:" + filenameShow + ", season:" + seasonNum + ", episode:" + episodeNum
            + ", file:" + fileNameString + " }";
    }
}
