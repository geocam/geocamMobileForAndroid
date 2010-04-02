package gov.nasa.arc.geocam.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	void clearQueue();
	
	boolean isUploading();
	int getQueueSize();	
	int lastUploadStatus();
	
	Location getLocation();
	void increaseLocationUpdateRate();
}
