\# ⚙️ CompressoIQ-Backend



CompressoIQ-Backend is the Spring Boot backend for the CompressoIQ Android application.



It handles:



\* user authentication

\* image compression requests

\* document compression requests

\* file history management

\* database storage and API communication for the Android app



\---



\## 🚀 Features



\### 🔐 Authentication



\* User Signup

\* User Login

\* Forgot Password support



\### 🖼️ Image Compression APIs



\* Upload image for compression

\* Compress images with target size logic

\* Support for JPG / PNG handling

\* Save compressed image details to history



\### 📄 Document Compression APIs



\* Upload and compress documents

\* Support for PDF and other supported file formats

\* Store compression results and metadata



\### 📜 History Management



\* Save compressed file records

\* Retrieve image compression history

\* Retrieve document compression history

\* Delete history items



\### 🔗 Android App Integration



\* REST API support for Android frontend

\* JSON request/response handling



\---



\## 🛠 Tech Stack



\* Java

\* Spring Boot

\* Spring Web

\* Spring Data JPA

\* Hibernate

\* MySQL

\* Maven



\---



\## 📂 Project Structure



src/

└── main/

├── java/com/saloni/aiphotocompressorbackend/

│   ├── controller/

│   ├── service/

│   ├── repository/

│   ├── entity/

│   ├── dto/

│   └── enums/

└── resources/

├── static/

├── templates/

├── application.properties

└── application-example.properties



\---



\## ⚙️ Configuration



Create your own `application.properties` file inside:



`src/main/resources/`



You can use the provided example file:



`application-example.properties`



Then replace the placeholder values with your own:



\* database name

\* database username

\* database password

\* email credentials (if required)



\---



\## ▶️ How to Run



\### 1. Clone the repository



`git clone https://github.com/Salonniii/CompressoIQ-Backend.git`



\### 2. Open in IntelliJ / STS / VS Code



\### 3. Configure MySQL database



Make sure MySQL is running and update your:



`src/main/resources/application.properties`



\### 4. Run the Spring Boot application



Run:



`mvn spring-boot:run`



or run the main application class directly from your IDE.



\---



\## 🔗 Related Repositories



\### Android Frontend



\[CompressoIQ-Android](https://github.com/Salonniii/CompressoIQ-Android)



\---



\## 👩‍💻 Author



\*\*Saloni Gupta\*\*



\---



\## 📌 Note



This backend is designed specifically for the CompressoIQ Android application and supports both image and document compression workflows with history tracking and authentication support.



