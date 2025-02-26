npm i // to install dependencies
npm start // to start the server
create a folder video outside and web and put all videos there
npm process-video.js // to convert videos into low, medium, high chunks each of 3sec


API Endpoints:
GET /api/feed?page=0&pageSize=5 - Get paginated video feed
GET /api/videos/:videoId - Get full video details with all chunks
GET /api/status - Check server status

