package gov.nasa.arc.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	boolean isUploading();
	List<String> getUploadQueue();
}
