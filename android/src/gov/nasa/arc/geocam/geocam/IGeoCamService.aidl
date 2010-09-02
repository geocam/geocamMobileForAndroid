// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	void addTrackToUploadQueue(in long trackId);
	void clearQueue();
	
	boolean isUploading();
	int getQueueSize();	
	int lastUploadStatus();
	
	Location getLocation();
	void increaseLocationUpdateRate();
	
	void startRecordingTrack();
	void stopRecordingTrack();
	void pauseTrack();
	void resumeTrack();
	boolean isRecordingTrack();
	boolean isTrackPaused();
	long currentTrackId();
	
	void applicationVisible();
	void applicationInvisible();
}
