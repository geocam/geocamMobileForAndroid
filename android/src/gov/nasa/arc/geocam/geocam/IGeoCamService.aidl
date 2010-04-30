package gov.nasa.arc.geocam.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
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
}
