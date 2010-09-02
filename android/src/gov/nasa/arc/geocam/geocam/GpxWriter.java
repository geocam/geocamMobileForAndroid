// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class GpxWriter extends StringWriter {
	private static final String PREAMBLE =
		"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
		"<gpx " +
		 	"version=\"1.1\" " +
		 	"creator=\"GeoCamMobile\" " +
		 	"xmlns=\"http://www.topografix.com/GPX/1/0\" " +
		 	"xmlns:geocam=\"http://geocam.arc.nasa.gov/schema/gpxExtensions/1\" " +
		 	"xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
		 	"xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"" +
		 ">";
	
	private static final String POSTAMBLE = "</gpx>\n";
	
	private static final String TRACK_START = "<trk>";
	private static final String TRACK_END = "</trk>";

	private static final String TRACK_SEG_START = "<trkseg>";
	private static final String TRACK_SEG_END = "</trkseg>";
	
	public GpxWriter() {
		super();
		
		this.append(PREAMBLE);
	}
	
	public void startTrack(String notes) {
		this.append(TRACK_START)
			.append("<desc>").append(notes).append("</desc>");
	}
	
	public void startSegment() {
		this.append(TRACK_SEG_START);
	}
	
	public void append(double lat, double lon, double alt, long time) {
		this.append("<trkpt")
			.append(" lat=\"").append(Double.toString(lat)).append("\"")
			.append(" lon=\"").append(Double.toString(lon)).append("\"")
			.append(">");
		this.append("<time>").append(dateToISO8601(time)).append("</time>");
		this.append("<ele>").append(Double.toString(alt)).append("</ele>");
		this.append("</trkpt>");
	}
	
	public void endSegment() {
		this.append(TRACK_SEG_END);
	}
	
	public void endTrack() {
		this.append(TRACK_END);
	}
	
	public void startTrackExtensions() {
		this.append("<extensions>");
	}
	
	public void appendIcon(String icon) {
		this.append("<geocam:icon>" + icon + "</geocam:icon>");
	}

	public void appendSolidDashed(boolean dashed) {
		this.append("<geocam:lineStyle>");
		if (dashed) {
			this.append("dashed");
		} else {
			this.append("solid");
		}
		
		this.append("</geocam:lineStyle>");
	}
	
	public void appendColor(int argbColor) {
		// This will be in ARGB (android color), convert to RGBA (html-ish)
		int rgbaColor = Integer.rotateLeft(argbColor, 8);
		this.append("<geocam:lineColor>#" + Integer.toHexString(rgbaColor) + "</geocam:lineColor>");
	}
	
	public void appendUID(String uid) {
		this.append("<geocam:uid>" + uid + "</geocam:uid>");
	}
	
	public void endTrackExtensions() {
		this.append("</extensions>");
	}
	
	public String toString() {
		return super.toString() + POSTAMBLE;
	}
	
	private static final SimpleDateFormat ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'");
	static {
		ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	// Convert a system-given millisecond timestamp to
	// a string that I can dump into sqlite. (UTC)
	public static String dateToISO8601(long time) {
		return ISO_DATE_FORMAT.format(new Date(time));
	}
	
}
