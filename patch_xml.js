const fs = require('fs');

const filePath = 'receiver/src/main/res/layout/activity_settings.xml';
let content = fs.readFileSync(filePath, 'utf-8');

const locationCard = `            <!-- Location Reminder Card -->
            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="16dp"
                app:cardCornerRadius="16dp"
                app:cardElevation="2dp"
                app:strokeWidth="0dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Location Reminders"
                        android:textStyle="bold"
                        android:textColor="#FF9500"
                        android:textSize="16sp"
                        android:layout_marginBottom="12dp" />
                    
                    <com.google.android.material.textfield.TextInputLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="Latitude (e.g. 13.75)">
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/editGeoLat"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="numberDecimal|numberSigned"/>
                    </com.google.android.material.textfield.TextInputLayout>
                    
                    <com.google.android.material.textfield.TextInputLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="Longitude (e.g. 100.5167)"
                        android:layout_marginTop="8dp">
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/editGeoLon"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="numberDecimal|numberSigned"/>
                    </com.google.android.material.textfield.TextInputLayout>
                    
                    <com.google.android.material.textfield.TextInputLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="Reminder Message"
                        android:layout_marginTop="8dp">
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/editGeoMsg"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="text"/>
                    </com.google.android.material.textfield.TextInputLayout>
                    
                    <Button
                        android:id="@+id/btnSaveGeo"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="Save Location Reminder"
                        android:layout_marginTop="12dp"
                        app:cornerRadius="8dp" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>

            <!-- Media Card -->
            <com.google.android.material.card.MaterialCardView`;

content = content.replace("            <!-- Media Card -->\n            <com.google.android.material.card.MaterialCardView", locationCard);

fs.writeFileSync(filePath, content, 'utf-8');
console.log('Updated XML');
