package gov.nasa.arc.geocam.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	boolean isUploading();
	List<String> getUploadQueue();
}
