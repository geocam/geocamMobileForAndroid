package gov.nasa.arc.geocam.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	void clearQueue();
	
	boolean isUploading();
	List<String> getUploadQueue();	
	int lastUploadStatus();
	
	Location getLocation();
}
